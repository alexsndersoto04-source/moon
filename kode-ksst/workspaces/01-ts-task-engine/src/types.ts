export type Priority = 'critical' | 'high' | 'medium' | 'low';

export interface TaskItem {
  id: string;
  title: string;
  priority: Priority;
  estimateMinutes: number;
  completed: boolean;
  tags: string[];
}
