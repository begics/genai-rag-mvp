const APP_URL = process.env.RAG_APP_URL || 'http://localhost:8080';
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1']);

function assertLocalUrl(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !LOCAL_HOSTS.has(url.hostname)) {
    throw new Error('Live Promptfoo evaluation is restricted to a local HTTP app URL.');
  }
  return url;
}

function parseAttributes(tag) {
  const attributes = {};
  for (const match of tag.matchAll(/([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
    attributes[match[1].toLowerCase()] = match[2] ?? match[3] ?? match[4] ?? '';
  }
  return attributes;
}

function decodeHtml(value) {
  return value
    .replaceAll('&amp;', '&')
    .replaceAll('&quot;', '"')
    .replaceAll('&#x27;', "'")
    .replaceAll('&#39;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>');
}

export class RagSession {
  constructor() {
    this.appUrl = assertLocalUrl(APP_URL);
    this.cookies = new Map();
  }

  cookieHeader(url) {
    const requestUrl = new URL(url);
    return [...this.cookies.values()]
      .filter((cookie) => {
        const domainMatches = requestUrl.hostname === cookie.domain
          || requestUrl.hostname.endsWith(`.${cookie.domain}`);
        const pathMatches = requestUrl.pathname.startsWith(cookie.path);
        const secureMatches = !cookie.secure
          || requestUrl.protocol === 'https:'
          || LOCAL_HOSTS.has(requestUrl.hostname);
        return domainMatches && pathMatches && secureMatches;
      })
      .map((cookie) => `${cookie.name}=${cookie.value}`)
      .join('; ');
  }

  storeCookies(response, url) {
    const responseUrl = new URL(url);
    for (const line of response.headers.getSetCookie()) {
      const [pair, ...attributes] = line.split(';');
      const separator = pair.indexOf('=');
      if (separator <= 0) continue;
      const name = pair.slice(0, separator).trim();
      const value = pair.slice(separator + 1).trim();
      const options = Object.fromEntries(attributes.map((attribute) => {
        const [key, ...rest] = attribute.trim().split('=');
        return [key.toLowerCase(), rest.join('=')];
      }));
      const domain = (options.domain || responseUrl.hostname).replace(/^\./, '');
      const path = options.path || '/';
      const key = `${domain};${path};${name}`;
      if (options['max-age'] === '0' || !value) {
        this.cookies.delete(key);
      } else {
        this.cookies.set(key, { name, value, domain, path, secure: 'secure' in options });
      }
    }
  }

  async fetch(url, options = {}) {
    let target = assertLocalUrl(url).href;
    let method = options.method || 'GET';
    let body = options.body;
    let headers = new Headers(options.headers || {});

    for (let redirects = 0; redirects <= 10; redirects++) {
      const cookie = this.cookieHeader(target);
      if (cookie) headers.set('Cookie', cookie);
      const response = await fetch(target, { method, body, headers, redirect: 'manual' });
      this.storeCookies(response, target);
      if (![301, 302, 303, 307, 308].includes(response.status)) return response;

      const location = response.headers.get('location');
      if (!location) return response;
      target = assertLocalUrl(new URL(location, target).href).href;
      if (response.status === 303 || ((response.status === 301 || response.status === 302) && method === 'POST')) {
        method = 'GET';
        body = undefined;
        headers = new Headers();
      }
    }
    throw new Error('Local app exceeded the allowed number of OIDC redirects.');
  }

  async login() {
    const loginPage = await this.fetch(new URL('/oauth2/authorization/keycloak', this.appUrl));
    const html = await loginPage.text();
    const formMatch = [...html.matchAll(/<form\b[^>]*>[\s\S]*?<\/form>/gi)]
      .map((match) => match[0])
      .find((form) => /login-actions\/authenticate/.test(form));
    if (!formMatch) {
      throw new Error('Could not find Keycloak login form; check the local OIDC setup.');
    }

    const formTag = formMatch.match(/<form\b[^>]*>/i)?.[0];
    const action = parseAttributes(formTag || '').action;
    if (!action) throw new Error('Keycloak login form has no action URL.');

    const fields = new URLSearchParams();
    for (const match of formMatch.matchAll(/<input\b[^>]*>/gi)) {
      const input = parseAttributes(match[0]);
      if (input.name && (input.type || 'text').toLowerCase() === 'hidden') {
        fields.set(input.name, decodeHtml(input.value || ''));
      }
    }

    const username = process.env.KEYCLOAK_ADMIN_USERNAME || 'admin';
    const password = process.env.KEYCLOAK_ADMIN_PASSWORD;
    if (!password) throw new Error('Set KEYCLOAK_ADMIN_PASSWORD in the local .env file.');
    fields.set('username', username);
    fields.set('password', password);

    const result = await this.fetch(new URL(decodeHtml(action), loginPage.url || this.appUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: fields.toString(),
    });
    if (!result.ok) throw new Error(`Local Keycloak login failed with HTTP ${result.status}.`);

    const identity = await this.get('/api/me');
    if (!identity.authorities?.includes('ROLE_ADMIN')) {
      throw new Error('Configured local Keycloak user is missing ROLE_ADMIN.');
    }
    return identity;
  }

  async get(path, headers = {}) {
    return this.json(await this.fetch(new URL(path, this.appUrl), { headers }));
  }

  async post(path, payload, headers = {}) {
    return this.json(await this.fetch(new URL(path, this.appUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
      body: JSON.stringify(payload),
    }));
  }

  async json(response) {
    const body = await response.text();
    if (!response.ok) {
      throw new Error(`Local RAG request failed with HTTP ${response.status}: ${body.slice(0, 500)}`);
    }
    return body ? JSON.parse(body) : {};
  }

  async createWorkspace(name) {
    const csrf = await this.get('/api/csrf');
    return this.post('/api/workspaces', { name }, { 'X-XSRF-TOKEN': csrf.token });
  }

  async indexText(workspaceId, source, text) {
    const csrf = await this.get('/api/csrf');
    const job = await this.post('/api/indexing-jobs', { source, text }, {
      'X-XSRF-TOKEN': csrf.token,
      'X-Workspace-ID': workspaceId,
    });
    for (let attempt = 0; attempt < 90; attempt++) {
      const status = await this.get(`/api/indexing-jobs/${job.id}`, {
        'X-Workspace-ID': workspaceId,
      });
      if (status.status === 'COMPLETED') return status;
      if (status.status === 'FAILED') {
        throw new Error(`Indexing failed for ${source}; provider error details are omitted.`);
      }
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
    throw new Error(`Indexing timed out for ${source}.`);
  }
}
