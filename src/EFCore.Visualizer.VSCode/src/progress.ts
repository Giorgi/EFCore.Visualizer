import * as vscode from 'vscode';

/** Wait this long before showing anything, so fast plans (SQLite) don't flash a notification. */
export const PROGRESS_DELAY_MS = 500;
/** From this many seconds on, the notification also says why it can take a while. */
export const PROGRESS_HINT_AFTER_S = 3;

export const PROGRESS_HINT = 'SQL Server, PostgreSQL and Oracle run the query to get the actual plan, so this takes as long as the query.';

export function progressMessage(text: string, seconds: number): string {
  if (seconds < 1) {
    return `${text}…`;
  }
  return `${text} (${seconds} s)${seconds >= PROGRESS_HINT_AFTER_S ? `. ${PROGRESS_HINT}` : ''}`;
}

type ShowProgress = (
  options: vscode.ProgressOptions,
  task: (progress: vscode.Progress<{ message?: string }>) => Thenable<void>,
) => Thenable<void>;

/**
 * Shows a notification with the elapsed time while `work` runs, once it has taken longer than PROGRESS_DELAY_MS.
 *
 * There is deliberately no Cancel button. The work is an evaluation inside the paused app. vsdbg does accept a DAP
 * "cancel", but while the app is in database code the abort only lands when that code returns, and after an abort
 * vsdbg refuses method calls until the user steps or continues.
 */
export async function withProgressNotification<T>(
  text: string,
  work: Thenable<T>,
  showProgress: ShowProgress = (options, task) => vscode.window.withProgress(options, task),
): Promise<T> {
  let finished = false;
  const settled = Promise.resolve(work).then(
    () => undefined,
    () => undefined,
  );
  void settled.then(() => (finished = true));

  const timer = setTimeout(() => {
    if (finished) {
      return;
    }
    void showProgress({ location: vscode.ProgressLocation.Notification, title: 'EFCore Visualizer' }, async (progress) => {
      const start = Date.now() - PROGRESS_DELAY_MS;
      const report = () => progress.report({ message: progressMessage(text, Math.floor((Date.now() - start) / 1000)) });
      report();
      const ticker = setInterval(report, 1000);
      try {
        await settled;
      } finally {
        clearInterval(ticker);
      }
    });
  }, PROGRESS_DELAY_MS);

  try {
    return await work;
  } finally {
    clearTimeout(timer);
  }
}
