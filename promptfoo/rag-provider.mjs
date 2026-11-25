import { RagSession } from './rag-session.mjs';

export default class RagProvider {
  constructor(options = {}) {
    this.providerId = options.id || 'rag-local-live';
    this.sessionPromise = undefined;
  }

  id() {
    return this.providerId;
  }

  async callApi(question) {
    const workspaceId = process.env.PROMPTFOO_WORKSPACE_ID;
    if (!workspaceId) {
      return { error: 'Run the live evaluation through npm run eval:rag:live.' };
    }

    this.sessionPromise ??= (async () => {
      const session = new RagSession();
      await session.login();
      return session;
    })();

    try {
      const session = await this.sessionPromise;
      const csrf = await session.get('/api/csrf');
      const result = await session.post('/api/query', { question }, {
        'X-XSRF-TOKEN': csrf.token,
        'X-Workspace-ID': workspaceId,
      });
      return {
        output: result.answer,
        metadata: { sources: result.sources || [] },
      };
    } catch (error) {
      this.sessionPromise = undefined;
      return { error: error.message };
    }
  }
}
