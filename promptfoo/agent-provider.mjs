import { RagSession } from './rag-session.mjs';

export default class AgentProvider {
  constructor(options = {}) {
    this.providerId = options.id || 'rag-local-agent-live';
    this.sessionPromise = undefined;
  }

  id() {
    return this.providerId;
  }

  async callApi(question) {
    const workspaceId = process.env.PROMPTFOO_WORKSPACE_ID;
    if (!workspaceId) {
      return { error: 'Run the live agent evaluation through npm run eval:agent:live.' };
    }

    this.sessionPromise ??= (async () => {
      const session = new RagSession();
      await session.login();
      return session;
    })();

    try {
      const session = await this.sessionPromise;
      const csrf = await session.get('/api/csrf');
      const result = await session.post('/api/agent/query', { question }, {
        'X-XSRF-TOKEN': csrf.token,
        'X-Workspace-ID': workspaceId,
      });
      return {
        output: result.answer,
        metadata: {
          sources: result.sources || [],
          steps: result.steps || [],
          comparison: result.comparison || null,
        },
      };
    } catch (error) {
      this.sessionPromise = undefined;
      return { error: error.message };
    }
  }
}
