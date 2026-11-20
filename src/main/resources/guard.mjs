import fs from 'node:fs';
import path from 'node:path';

const root = fs.realpathSync(process.env.OMP_WORKSPACE);
const marker = process.env.OMP_GUARD_MARKER;
const nonce = process.env.OMP_GUARD_NONCE;
const names = ['workspace_list', 'workspace_read', 'workspace_write', 'workspace_search', 'workspace_mkdir', 'workspace_delete'];
const forbidden = name => ['.git', '.omp', '.agents', '.claude', '.codex', '.cursor', '.gemini'].includes(name.toLowerCase()) || /^\.env(?:\.|$)/i.test(name);

function resolve(input, missing = false) {
  if (typeof input !== 'string' || input.includes('\0') || input.includes('\\') || /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|\/)/.test(input)) throw Error('Relative workspace paths only');
  const parts = input.split('/');
  if (parts.some(p => p === '..' || forbidden(p))) throw Error('Forbidden path');
  let target = root;
  for (const part of parts.filter(p => p && p !== '.')) {
    target = path.join(target, part);
    try {
      const st = fs.lstatSync(target);
      if (st.isSymbolicLink() || (st.isFile() && st.nlink !== 1)) throw Error('Links are forbidden');
    } catch (e) {
      if (!(missing && e.code === 'ENOENT')) throw e;
    }
  }
  return target;
}
function text(value) { return { content: [{ type: 'text', text: value }], details: {} }; }
function mark() { fs.appendFileSync(marker, `used:${nonce}\n`); }
export default function(pi) {
  const z = pi.zod;
  pi.on('session_start', async () => {
    await pi.setActiveTools(names);
    const active = pi.getActiveTools();
    if (active.length !== names.length || names.some(n => !active.includes(n))) throw Error('Guard tool activation failed');
    fs.writeFileSync(marker, `ready:${nonce}\n`);
  });
  pi.on('tool_call', e => names.includes(e.toolName) ? undefined : { block: true, reason: 'Only guarded workspace tools are permitted' });
  pi.registerTool({ name: names[0], label: 'List workspace', description: 'List entries in a workspace directory; paths are relative.', parameters: z.object({path: z.string()}), async execute(_id, p) {
    mark(); return text(fs.readdirSync(resolve(p.path), {withFileTypes:true}).filter(e => !e.isSymbolicLink() && !forbidden(e.name)).map(e => e.name + (e.isDirectory() ? '/' : '')).join('\n').slice(0, 100000));
  }});
  pi.registerTool({ name: names[1], label: 'Read workspace', description: 'Read a UTF-8 workspace file, up to 200 KB.', parameters: z.object({path: z.string()}), async execute(_id, p) {
    mark(); const file = resolve(p.path); const st = fs.statSync(file); if (!st.isFile() || st.size > 200000) throw Error('Not a regular file or file too large'); return text(fs.readFileSync(file, 'utf8'));
  }});
  pi.registerTool({ name: names[2], label: 'Write workspace', description: 'Create or replace a UTF-8 workspace file. Parent directory must already exist.', parameters: z.object({path: z.string(), content: z.string()}), async execute(_id, p) {
    mark(); if (Buffer.byteLength(p.content) > 1000000) throw Error('File too large'); const file = resolve(p.path, true); if (file === root) throw Error('Not a file'); const fd = fs.openSync(file, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_TRUNC | fs.constants.O_NOFOLLOW, 0o644); try { fs.writeFileSync(fd, p.content); } finally { fs.closeSync(fd); } return text('Written');
  }});
  pi.registerTool({ name: names[3], label: 'Search workspace', description: 'Find literal text in regular UTF-8 workspace files.', parameters: z.object({query: z.string()}), async execute(_id, p) {
    mark(); if (!p.query || p.query.length > 1000) throw Error('Invalid query'); const found = []; let scanned = 0;
    function walk(dir) { for (const e of fs.readdirSync(dir, {withFileTypes:true})) { if (found.length >= 100 || scanned >= 10000) return; if (e.isSymbolicLink() || forbidden(e.name)) continue; const f = path.join(dir,e.name); if (e.isDirectory()) walk(f); else if (e.isFile()) { scanned++; const s = fs.lstatSync(f); if (s.nlink !== 1 || s.size > 200000) continue; const content = fs.readFileSync(f,'utf8'); if (content.includes('\0')) continue; content.split('\n').forEach((line,i) => { if (line.includes(p.query) && found.length < 100) found.push(`${path.relative(root,f)}:${i+1}: ${line.slice(0,1000)}`); }); } } }
    walk(root); return text(found.join('\n'));
  }});
  pi.registerTool({ name: names[4], label: 'Create workspace directory', description: 'Create a workspace directory and any missing parents.', parameters: z.object({path: z.string()}), async execute(_id, p) {
    mark(); const dir = resolve(p.path, true); if (dir === root) throw Error('Workspace root cannot be created'); fs.mkdirSync(dir, {recursive:true}); return text('Directory created');
  }});
  pi.registerTool({ name: names[5], label: 'Delete workspace entry', description: 'Delete one workspace file or empty directory.', parameters: z.object({path: z.string()}), async execute(_id, p) {
    mark(); const target = resolve(p.path); if (target === root) throw Error('Workspace root cannot be deleted'); const st = fs.lstatSync(target); if (st.isDirectory()) fs.rmdirSync(target); else if (st.isFile()) fs.unlinkSync(target); else throw Error('Not a regular file or directory'); return text('Deleted');
  }});
}
