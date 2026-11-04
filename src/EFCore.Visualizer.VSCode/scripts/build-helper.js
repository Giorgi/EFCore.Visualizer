// Builds the debugger-agnostic debuggee helper and lays it out like the VSIX does:
//   dist/netcoreapp/EFCore.Visualizer.DebuggeeHelper.dll
//   dist/Resources/<Common|SQLite|SqlServer|Postgres|Oracle>/...
// QueryPlanHelper resolves templates from "../Resources" relative to its own assembly.
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const src = path.resolve(root, '..');
const project = path.join(src, 'EFCore.Visualizer.DebuggeeHelper', 'EFCore.Visualizer.DebuggeeHelper.csproj');
const buildOut = path.join(root, 'out', 'helper-build');
const dist = path.join(root, 'dist');

execFileSync('dotnet', ['build', project, '-c', 'Release', '-o', buildOut], { stdio: 'inherit' });

fs.rmSync(dist, { recursive: true, force: true });
fs.mkdirSync(path.join(dist, 'netcoreapp'), { recursive: true });
// Copy only the helper itself: EF Core and the ADO.NET provider must come from the debuggee.
for (const file of ['EFCore.Visualizer.DebuggeeHelper.dll', 'EFCore.Visualizer.DebuggeeHelper.pdb']) {
  fs.copyFileSync(path.join(buildOut, file), path.join(dist, 'netcoreapp', file));
}
fs.cpSync(path.join(src, 'EFCore.Visualizer', 'Resources'), path.join(dist, 'Resources'), { recursive: true });
console.log(`Helper laid out in ${dist}`);
