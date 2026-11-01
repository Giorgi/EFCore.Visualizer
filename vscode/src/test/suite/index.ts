import * as path from 'path';
import * as fs from 'fs';
import Mocha from 'mocha';

export function run(): Promise<void> {
  const mocha = new Mocha({ ui: 'bdd', color: true, timeout: 10 * 60 * 1000 });
  const testsRoot = __dirname;
  for (const file of fs.readdirSync(testsRoot)) {
    if (file.endsWith('.test.js')) {
      mocha.addFile(path.join(testsRoot, file));
    }
  }
  return new Promise((resolve, reject) => {
    mocha.run((failures) => (failures > 0 ? reject(new Error(`${failures} tests failed.`)) : resolve()));
  });
}
