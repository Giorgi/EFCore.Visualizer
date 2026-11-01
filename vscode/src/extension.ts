import * as vscode from 'vscode';
import * as path from 'path';

// Spike stage: only the debuggee round-trip (see debuggee.ts and the integration test) exists.
// The "Visualize query plan" menu command and the webview come after that test passes.

export function helperAssemblyPath(context: vscode.ExtensionContext): string {
  return path.join(context.extensionPath, 'dist', 'netcoreapp', 'EFCore.Visualizer.DebuggeeHelper.dll');
}

export function activate(_context: vscode.ExtensionContext): void {
  // Intentionally empty for now.
}

export function deactivate(): void {}
