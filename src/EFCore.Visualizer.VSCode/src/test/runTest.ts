import * as cp from 'child_process';
import * as path from 'path';
import { downloadAndUnzipVSCode, resolveCliArgsFromVSCodeExecutablePath, runTests } from '@vscode/test-electron';

async function main(): Promise<void> {
  const extensionDevelopmentPath = path.resolve(__dirname, '../..');
  const extensionTestsPath = path.resolve(__dirname, './suite/index');
  const workspace = path.resolve(extensionDevelopmentPath, '../../samples/SampleApp');
  // VSCODE_VERSION=1.90.0 tests the oldest supported VS Code. Each version gets its own extensions and settings, so
  // it gets the newest C# extension compatible with it, as a user of that version would.
  const version = process.env.VSCODE_VERSION ?? 'stable';
  const suffix = version === 'stable' ? '' : `-${version}`;
  const userDataDir = path.resolve(extensionDevelopmentPath, `.vscode-test/user-data${suffix}`);
  const extensionsDir = path.resolve(extensionDevelopmentPath, `.vscode-test/extensions${suffix}`);

  const vscodeExecutablePath = await downloadAndUnzipVSCode(version);

  // The real C# extension provides the `coreclr` debug type (vsdbg).
  const [cli, ...cliArgs] = resolveCliArgsFromVSCodeExecutablePath(vscodeExecutablePath);
  const install = cp.spawnSync(
    cli,
    [...cliArgs, '--extensions-dir', extensionsDir, '--user-data-dir', userDataDir, '--install-extension', 'ms-dotnettools.csharp', '--force'],
    { encoding: 'utf-8', stdio: 'inherit', shell: process.platform === 'win32' },
  );
  if (install.status !== 0) {
    throw new Error(`Failed to install ms-dotnettools.csharp (exit ${install.status})`);
  }

  await runTests({
    vscodeExecutablePath,
    extensionDevelopmentPath,
    extensionTestsPath,
    launchArgs: [
      workspace,
      '--extensions-dir', extensionsDir,
      '--user-data-dir', userDataDir,
      '--disable-workspace-trust',
      '--skip-welcome',
      '--skip-release-notes',
      ...(process.platform === 'linux' && process.getuid?.() === 0 ? ['--no-sandbox'] : []),
    ],
    extensionTestsEnv: { EFCORE_VISUALIZER_TEST_WORKSPACE: workspace },
  });
}

main().catch((err) => {
  console.error('Failed to run tests:', err);
  process.exit(1);
});
