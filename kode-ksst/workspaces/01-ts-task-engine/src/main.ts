import { TaskScheduler } from './scheduler.ts';

const scheduler = new TaskScheduler();

scheduler.add({ id: 'TSK-1', title: 'Implement AST symbol parser', priority: 'high', estimateMinutes: 45, completed: false, tags: ['compiler'] });
scheduler.add({ id: 'TSK-2', title: 'Fix memory leak in worker pool', priority: 'critical', estimateMinutes: 25, completed: false, tags: ['runtime', 'perf'] });
scheduler.add({ id: 'TSK-3', title: 'Write integration tests', priority: 'medium', estimateMinutes: 30, completed: true, tags: ['qa'] });
scheduler.add({ id: 'TSK-4', title: 'Add WebSocket heartbeat', priority: 'critical', estimateMinutes: 15, completed: false, tags: ['network'] });

console.log('=== NEXUS TASK SCHEDULER ===');
console.log('Summary:', JSON.stringify(scheduler.summary(), null, 2));
console.log('\nExecution Queue (by Priority & Shortest-Job-First):');
for (const task of scheduler.pendingOrdered()) {
  console.log(`  [${task.priority.toUpperCase()}] ${task.id} — ${task.title} (${task.estimateMinutes}m)`);
}
