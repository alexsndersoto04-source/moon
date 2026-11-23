import type { Priority, TaskItem } from './types.ts';

const WEIGHT: Record<Priority, number> = {
  critical: 4,
  high: 3,
  medium: 2,
  low: 1,
};

export class TaskScheduler {
  private items: TaskItem[] = [];

  add(item: TaskItem): void {
    this.items.push(item);
  }

  complete(id: string): boolean {
    const found = this.items.find((t) => t.id === id);
    if (!found) return false;
    found.completed = true;
    return true;
  }

  pendingOrdered(): TaskItem[] {
    return this.items
      .filter((t) => !t.completed)
      .sort((a, b) => WEIGHT[b.priority] - WEIGHT[a.priority] || a.estimateMinutes - b.estimateMinutes);
  }

  summary() {
    const total = this.items.length;
    const done = this.items.filter((t) => t.completed).length;
    const remainingMinutes = this.pendingOrdered().reduce((acc, t) => acc + t.estimateMinutes, 0);
    return { total, done, pending: total - done, remainingMinutes };
  }
}
