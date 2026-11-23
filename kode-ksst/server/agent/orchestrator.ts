import { BrainConfig, callBrain, ConversationMessage } from './brainAdapter.ts';
import { buildSystemPrompt, IdeLiveSnapshot } from './systemPrompt.ts';
import { executeIdeTool, IDE_TOOLS, MUTATING_OR_EXEC_TOOLS, UiSideEffect } from './tools.ts';

export interface AgentRunRequest {
  runId: string;
  brain: BrainConfig;
  prompt: string;
  history: Array<{ role: 'user' | 'assistant'; content: string }>;
  snapshot: IdeLiveSnapshot;
  autoApprove: boolean;
  maxSteps?: number;
}

const activeControllers = new Map<string, AbortController>();
const pendingApprovals = new Map<string, (approved: boolean) => void>();

export function abortAgentRun(runId: string): boolean {
  const ctrl = activeControllers.get(runId);
  if (ctrl) {
    ctrl.abort();
    activeControllers.delete(runId);
    return true;
  }
  return false;
}

export function resolveToolApproval(approvalId: string, approved: boolean): boolean {
  const resolver = pendingApprovals.get(approvalId);
  if (!resolver) return false;
  pendingApprovals.delete(approvalId);
  resolver(approved);
  return true;
}

export async function runAgentLoop(
  req: AgentRunRequest,
  sendEvent: (event: string, data: any) => void
): Promise<void> {
  const controller = new AbortController();
  activeControllers.set(req.runId, controller);

  try {
    const systemPrompt = await buildSystemPrompt(req.snapshot);
    const messages: ConversationMessage[] = [{ role: 'system', content: systemPrompt }];

    for (const h of req.history.slice(-14)) {
      if (h.content.trim()) {
        messages.push({ role: h.role, content: h.content });
      }
    }
    messages.push({ role: 'user', content: req.prompt });

    const maxSteps = Math.min(Math.max(req.maxSteps || 20, 1), 35);
    const emitUi = (ev: UiSideEffect) => sendEvent('ui_event', ev);

    for (let step = 1; step <= maxSteps; step++) {
      if (controller.signal.aborted) {
        sendEvent('aborted', { step });
        break;
      }

      sendEvent('step_start', { step, maxSteps });

      const brainResp = await callBrain(req.brain, messages, IDE_TOOLS, controller.signal);

      if (brainResp.text) {
        sendEvent('assistant_message', {
          step,
          text: brainResp.text,
          hasToolCalls: brainResp.toolCalls.length > 0,
          usage: brainResp.usage,
        });
      }

      messages.push({
        role: 'assistant',
        content: brainResp.text,
        toolCalls: brainResp.toolCalls.length > 0 ? brainResp.toolCalls : undefined,
      });

      if (brainResp.toolCalls.length === 0) {
        sendEvent('done', { step, usage: brainResp.usage });
        break;
      }

      for (const tc of brainResp.toolCalls) {
        if (controller.signal.aborted) break;

        sendEvent('tool_call', {
          step,
          toolCallId: tc.id,
          name: tc.name,
          arguments: tc.arguments,
        });

        // Interactive approval check
        if (!req.autoApprove && MUTATING_OR_EXEC_TOOLS.has(tc.name)) {
          const approvalId = `${req.runId}:${tc.id}`;
          sendEvent('approval_required', {
            approvalId,
            toolCallId: tc.id,
            name: tc.name,
            arguments: tc.arguments,
          });
          const approved = await new Promise<boolean>((resolve) => {
            pendingApprovals.set(approvalId, resolve);
            controller.signal.addEventListener('abort', () => {
              pendingApprovals.delete(approvalId);
              resolve(false);
            });
          });
          if (!approved) {
            const rejectedMsg = JSON.stringify({ rejectedByUser: true, reason: 'User denied tool execution.' });
            sendEvent('tool_result', {
              step,
              toolCallId: tc.id,
              name: tc.name,
              ok: false,
              output: rejectedMsg,
            });
            messages.push({
              role: 'tool',
              toolCallId: tc.id,
              toolName: tc.name,
              content: rejectedMsg,
            });
            continue;
          }
        }

        try {
          const resultStr = await executeIdeTool(tc.name, tc.arguments, {
            snapshot: req.snapshot,
            emitUi,
          });
          sendEvent('tool_result', {
            step,
            toolCallId: tc.id,
            name: tc.name,
            ok: true,
            output: resultStr.length > 8000 ? resultStr.slice(0, 8000) + '\n... [truncated for UI]' : resultStr,
          });
          messages.push({
            role: 'tool',
            toolCallId: tc.id,
            toolName: tc.name,
            content: resultStr.length > 24000 ? resultStr.slice(0, 24000) + '\n... [truncated]' : resultStr,
          });
        } catch (err: any) {
          const errMsg = JSON.stringify({ error: err.message || String(err) });
          sendEvent('tool_result', {
            step,
            toolCallId: tc.id,
            name: tc.name,
            ok: false,
            output: errMsg,
          });
          messages.push({
            role: 'tool',
            toolCallId: tc.id,
            toolName: tc.name,
            content: errMsg,
          });
        }
      }

      if (step === maxSteps) {
        sendEvent('done', { step, reachedMaxSteps: true });
      }
    }
  } catch (err: any) {
    if (err.name === 'AbortError' || controller.signal.aborted) {
      sendEvent('aborted', {});
    } else {
      sendEvent('error', { message: err.message || String(err) });
    }
  } finally {
    activeControllers.delete(req.runId);
  }
}
