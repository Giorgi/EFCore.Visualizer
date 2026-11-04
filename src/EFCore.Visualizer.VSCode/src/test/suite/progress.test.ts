import * as assert from 'assert';
import * as vscode from 'vscode';
import { PROGRESS_HINT, withProgressNotification } from '../../progress';

const delay = <T>(ms: number, value?: T) => new Promise<T>((resolve) => setTimeout(() => resolve(value as T), ms));

/** Stands in for vscode.window.withProgress and records what would be shown. */
function recorder() {
  const shown: { title?: string; messages: string[] }[] = [];
  const showProgress = (options: vscode.ProgressOptions, task: (p: vscode.Progress<{ message?: string }>) => Thenable<void>) => {
    const entry = { title: options.title, messages: [] as string[] };
    shown.push(entry);
    return task({ report: ({ message }) => message && entry.messages.push(message) });
  };
  return { shown, showProgress };
}

describe('progress notification while getting the plan', () => {
  it('shows nothing when the work is quick', async () => {
    const { shown, showProgress } = recorder();
    assert.strictEqual(await withProgressNotification('Getting the query plan', delay(100, 'plan.html'), showProgress), 'plan.html');
    await delay(600); // past the delay: still nothing
    assert.deepStrictEqual(shown, []);
  });

  it('shows the elapsed time, and from 3 s why it takes long', async () => {
    const { shown, showProgress } = recorder();
    assert.strictEqual(await withProgressNotification('Getting the query plan', delay(4200, 'plan.html'), showProgress), 'plan.html');
    assert.strictEqual(shown.length, 1, 'one notification');
    const { title, messages } = shown[0];
    console.log('progress =>', JSON.stringify(messages));
    assert.strictEqual(title, 'EFCore Visualizer');
    assert.strictEqual(messages[0], 'Getting the query plan…');
    assert.ok(messages.includes('Getting the query plan (2 s)'), 'no update at 2 s');
    assert.strictEqual(messages[messages.length - 1], `Getting the query plan (3 s). ${PROGRESS_HINT}`);
    const count = messages.length;
    await delay(1200);
    assert.strictEqual(messages.length, count, 'no more updates once the work is done');
  });

  it('passes errors through, after showing the notification if it was slow', async () => {
    const { shown, showProgress } = recorder();
    await assert.rejects(withProgressNotification('Getting the query plan', Promise.reject(new Error('fast failure')), showProgress), /fast failure/);
    await assert.rejects(
      withProgressNotification('Getting the query plan', delay(800).then(() => Promise.reject(new Error('slow failure'))), showProgress),
      /slow failure/,
    );
    assert.strictEqual(shown.length, 1, 'only the slow failure showed a notification');
  });

  it('works with the real notification', async () => {
    assert.strictEqual(await withProgressNotification('Getting the query plan', delay(1200, 42)), 42);
  });
});
