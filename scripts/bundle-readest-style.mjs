import fs from 'fs';
import path from 'path';
import { spawnSync } from 'child_process';
import { fileURLToPath, pathToFileURL } from 'url';

const ESBUILD_VERSION = '0.24.2';
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const toolsEsbuildDir = path.join(root, '.tools', `esbuild-${ESBUILD_VERSION}`);

async function loadBuild() {
  try {
    const mod = await import('esbuild');
    return mod.build;
  } catch {
    const esbuildEntry = path.join(
      toolsEsbuildDir,
      'node_modules',
      'esbuild',
      'lib',
      'main.js',
    );
    if (!fs.existsSync(esbuildEntry)) {
      const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
      const result = spawnSync(
        npm,
        ['install', '--prefix', toolsEsbuildDir, `esbuild@${ESBUILD_VERSION}`],
        { cwd: root, stdio: 'inherit' },
      );
      if (result.status !== 0) {
        throw new Error(
          `Failed to install esbuild@${ESBUILD_VERSION} into ${toolsEsbuildDir}`,
        );
      }
    }
    const mod = await import(pathToFileURL(esbuildEntry).href);
    return mod.build;
  }
}

const build = await loadBuild();

const stub = (name) => path.join(root, 'scripts', 'readest-style', 'stubs', name);

const aliases = {
  '@/utils/misc': stub('misc.js'),
  '@/styles/themes': stub('themes.js'),
  '@/styles/fonts': stub('fonts.js'),
  '@/services/constants': stub('constants.js'),
  '@/utils/dialogueHighlight': stub('dialogueHighlight.js'),
  '@/utils/ambientLight': stub('ambientLight.js'),
  '@/utils/inlineTags': stub('inlineTags.js'),
  '@/utils/scrollable': stub('scrollable.js'),
  '@/types/book': path.join(root, 'scripts', 'readest-style', 'stubs', 'book-types.js'),
};

const relativeStubs = {
  './misc': stub('misc.js'),
  './dialogueHighlight': stub('dialogueHighlight.js'),
  './ambientLight': stub('ambientLight.js'),
  './inlineTags': stub('inlineTags.js'),
  './scrollable': stub('scrollable.js'),
};

const stubPlugin = {
  name: 'readest-style-stubs',
  setup(buildApi) {
    buildApi.onResolve({ filter: /.*/ }, (args) => {
      const mapped = aliases[args.path] || relativeStubs[args.path];
      if (mapped) {
        return { path: mapped };
      }
      return undefined;
    });
  },
};

await build({
  absWorkingDir: root,
  entryPoints: [path.join(root, '.tools', 'readest-src', 'utils', 'style.ts')],
  outfile: path.join(root, 'android', 'app', 'src', 'main', 'assets', 'foliate', 'readest-style.mjs'),
  bundle: true,
  format: 'esm',
  platform: 'browser',
  target: 'es2020',
  alias: aliases,
  plugins: [stubPlugin],
  banner: {
    js: `/*
readest style.ts (AGPL-3.0) bundled for Android reflow
source: https://github.com/readest/readest
foliate-js MIT: see LICENSE in this folder
*/`,
  },
});
