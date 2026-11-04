import * as assert from 'assert';
import * as vscode from 'vscode';
import { LATER, NEVER, RATE, RATING_MESSAGE, RatingPrompt, nextAskAfter, reviewUrl } from '../../rating';

/** globalState stand-in. It stores JSON, as VS Code does, so undefined fields disappear. */
class FakeState implements vscode.Memento {
  private readonly values = new Map<string, string>();
  keys(): readonly string[] {
    return [...this.values.keys()];
  }
  get<T>(key: string, defaultValue?: T): T | undefined {
    const value = this.values.get(key);
    return value === undefined ? defaultValue : (JSON.parse(value) as T);
  }
  update(key: string, value: unknown): Thenable<void> {
    this.values.set(key, JSON.stringify(value));
    return Promise.resolve();
  }
}

/** A user who answers every prompt with `answer` (undefined: closes the notification). */
function user(answer: string | undefined) {
  const asked: number[] = [];
  const opened: string[] = [];
  let plans = 0;
  const state = new FakeState();
  let prompt = newSession();
  function newSession() {
    return new RatingPrompt(
      state,
      'https://review',
      (message, ...actions) => {
        assert.strictEqual(message, RATING_MESSAGE);
        assert.deepStrictEqual(actions, [RATE, LATER, NEVER]);
        asked.push(plans);
        return Promise.resolve(answer);
      },
      (url) => {
        opened.push(url);
        return Promise.resolve(true);
      },
    );
  }
  return {
    asked,
    opened,
    /** Shows `count` plans, each in a new VS Code session unless `sameSession`. */
    async showPlans(count: number, sameSession = false) {
      for (let i = 0; i < count; i++) {
        if (!sameSession) {
          prompt = newSession();
        }
        plans++;
        await prompt.planShown();
        await new Promise((resolve) => setImmediate(resolve)); // let the answer be handled
      }
    },
  };
}

describe('rating prompt', () => {
  it('asks at 5, 25, then every 50 up to 1000', () => {
    assert.deepStrictEqual([0, 4, 5, 24, 25, 74, 75, 124, 974, 975, 1000].map(nextAskAfter), [5, 5, 25, 25, 75, 75, 125, 125, 975, undefined, undefined]);
  });

  it('"Later" asks again at every step, 21 times in all', async () => {
    const u = user(LATER);
    await u.showPlans(1100);
    assert.deepStrictEqual(u.asked.slice(0, 5), [5, 25, 75, 125, 175]);
    assert.strictEqual(u.asked.length, 21);
    assert.strictEqual(u.asked[u.asked.length - 1], 975);
    assert.deepStrictEqual(u.opened, []);
  });

  it('closing the notification counts as "Later"', async () => {
    const u = user(undefined);
    await u.showPlans(80);
    assert.deepStrictEqual(u.asked, [5, 25, 75]);
  });

  it('"Rate" opens the review page and never asks again', async () => {
    const u = user(RATE);
    await u.showPlans(200);
    assert.deepStrictEqual(u.asked, [5]);
    assert.deepStrictEqual(u.opened, ['https://review']);
  });

  it(`"${NEVER}" never asks again`, async () => {
    const u = user(NEVER);
    await u.showPlans(200);
    assert.deepStrictEqual(u.asked, [5]);
    assert.deepStrictEqual(u.opened, []);
  });

  it('asks at most once per session, and catches up in the next one', async () => {
    const u = user(LATER);
    await u.showPlans(30, true); // one long session: 5 asked, 25 skipped
    assert.deepStrictEqual(u.asked, [5]);
    await u.showPlans(1); // the next session asks at once
    assert.deepStrictEqual(u.asked, [5, 31]);
    await u.showPlans(50);
    assert.deepStrictEqual(u.asked, [5, 31, 75]);
  });

  it('links to the extension\'s Marketplace reviews', () => {
    assert.strictEqual(
      reviewUrl('GiorgiDalakishvili.efcore-visualizer'),
      'https://marketplace.visualstudio.com/items?itemName=GiorgiDalakishvili.efcore-visualizer&ssr=false#review-details',
    );
  });
});
