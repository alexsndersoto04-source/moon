export type BrainProvider = 'openai' | 'anthropic' | 'gemini';

export interface BrainConfig {
  provider: BrainProvider;
  apiKey: string;
  baseUrl?: string;
  model: string;
  temperature?: number;
  maxTokens?: number;
}

export interface ProjectInfo {
  id: string;
  name: string;
  description: string;
  primaryLanguage: string;
  entryFile?: string;
  fileCount: number;
  hasGit: boolean;
  updatedAt: string;
}

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  size?: number;
  language?: string;
  children?: FileNode[];
}

export interface SymbolItem {
  name: string;
  kind: 'function' | 'class' | 'method' | 'variable' | 'interface' | 'struct' | 'section';
  line: number;
  detail?: string;
}

export interface EditorTab {
  path: string;
  content: string;
  savedContent: string;
  language: string;
  dirty: boolean;
  symbols: SymbolItem[];
  previousAgentContent?: string | null;
}

export interface DiagnosticItem {
  file: string;
  line: number;
  column: number;
  severity: 'error' | 'warning' | 'info';
  message: string;
  source: string;
}

export interface ExecutionResult {
  success: boolean;
  exitCode: number;
  language: string;
  command: string;
  stdout: string;
  stderr: string;
  durationMs: number;
  previewUrl?: string;
  timedOut?: boolean;
  timestamp?: string;
}

export interface RuntimeCapability {
  id: string;
  label: string;
  available: boolean;
  version?: string;
  extensions: string[];
}

export interface CheckpointInfo {
  id: string;
  label: string;
  createdAt: string;
  fileCount: number;
}

export interface SearchMatch {
  file: string;
  line: number;
  column: number;
  preview: string;
}

export interface AgentToolCallLog {
  toolCallId: string;
  name: string;
  arguments: Record<string, any>;
  status: 'running' | 'ok' | 'error' | 'awaiting_approval';
  output?: string;
  approvalId?: string;
}

export interface AgentChatItem {
  id: string;
  role: 'user' | 'assistant' | 'system_notice';
  content: string;
  timestamp: string;
  step?: number;
  toolCalls?: AgentToolCallLog[];
}

export type SidebarTab = 'explorer' | 'search' | 'git' | 'runner' | 'outline';
export type BottomTab = 'output' | 'terminal' | 'problems' | 'preview';
