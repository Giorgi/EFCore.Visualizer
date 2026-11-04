import * as vscode from 'vscode';

/** First asks after this many plans, then after the second, then every RATING_EVERY plans up to RATING_LAST. */
export const RATING_FIRST = 5;
export const RATING_SECOND = 25;
export const RATING_EVERY = 50;
export const RATING_LAST = 1000;

export const RATING_MESSAGE = 'Enjoying EFCore Visualizer? A rating on the Marketplace helps others find it.';
export const RATE = 'Rate';
export const LATER = 'Later';
export const NEVER = "Don't Ask Again";

const STATE_KEY = 'efcoreVisualizer.rating';

interface RatingState {
  /** Plans shown so far, in all sessions. */
  plans: number;
  /** The plan count at which to ask next; undefined once there's nothing left to ask. */
  nextAsk?: number;
}

/** The first ask after `plans` plans: 5, 25, then every 50 (75, 125, ...). Undefined past RATING_LAST. */
export function nextAskAfter(plans: number): number | undefined {
  const next =
    plans < RATING_FIRST ? RATING_FIRST
    : plans < RATING_SECOND ? RATING_SECOND
    : RATING_SECOND + (Math.floor((plans - RATING_SECOND) / RATING_EVERY) + 1) * RATING_EVERY;
  return next <= RATING_LAST ? next : undefined;
}

type ShowPrompt = (message: string, ...actions: string[]) => Thenable<string | undefined>;

/**
 * Asks for a Marketplace rating after successful plans. "Rate" and "Don't Ask Again" end it for good; "Later" or
 * closing the notification asks again at the next step. It asks at most once per VS Code session, so a skipped
 * step is caught up in a later session.
 */
export class RatingPrompt {
  private askedThisSession = false;

  constructor(
    private readonly state: vscode.Memento,
    private readonly reviewUrl: string,
    private readonly showPrompt: ShowPrompt = (message, ...actions) => vscode.window.showInformationMessage(message, ...actions),
    private readonly openUrl: (url: string) => Thenable<boolean> = (url) => vscode.env.openExternal(vscode.Uri.parse(url)),
  ) {}

  /**
   * Call after a plan was shown (not after an error or the SQL-only page). Returns once the state is saved; the
   * prompt itself, if any, isn't waited for.
   */
  async planShown(): Promise<void> {
    const current = this.read();
    const plans = current.plans + 1;
    if (current.nextAsk === undefined || plans < current.nextAsk || this.askedThisSession) {
      await this.write({ ...current, plans });
      return;
    }
    this.askedThisSession = true;
    // Saved before asking: if VS Code closes while the notification is open, that counts as "Later".
    await this.write({ plans, nextAsk: nextAskAfter(plans) });
    void this.ask();
  }

  private async ask(): Promise<void> {
    const answer = await this.showPrompt(RATING_MESSAGE, RATE, LATER, NEVER);
    if (answer === RATE || answer === NEVER) {
      await this.write({ ...this.read(), nextAsk: undefined });
    }
    if (answer === RATE) {
      await this.openUrl(this.reviewUrl);
    }
  }

  private read(): RatingState {
    return this.state.get<RatingState>(STATE_KEY) ?? { plans: 0, nextAsk: RATING_FIRST };
  }

  private write(state: RatingState): Thenable<void> {
    return this.state.update(STATE_KEY, state);
  }
}

export function reviewUrl(extensionId: string): string {
  return `https://marketplace.visualstudio.com/items?itemName=${encodeURIComponent(extensionId)}&ssr=false#review-details`;
}
