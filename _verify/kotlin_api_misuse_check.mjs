// kotlin_api_misuse_check.mjs
// 针对这次 GitHub Actions 真实报错的三类问题，做一次全项目扫描：
//   A. 同包内 private 顶层函数/属性被其它文件访问
//   B. 尾随 lambda 绑定错位（函数最后一个参数不是 lambda，却被尾随 lambda 调用）
//   C. 明显类型不匹配（List<String> 传给期望 List<DateKey> 的位置）
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = 'E:\\dsh\\duanlian\\android';
const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'build' || name === '.gradle') continue;
      walk(full);
    } else if (name.endsWith('.kt')) files.push(full);
  }
})(ROOT);

const parsed = files.map(f => {
  const text = readFileSync(f, 'utf8');
  const pkg = (text.match(/^package\s+([\w.]+)/m) || [])[1] || '';
  return { file: f, rel: relative(ROOT, f), text, pkg, lines: text.split('\n') };
});

const problems = [];

// ---- A. private 顶层声明被跨文件使用 ----
const privateDecls = []; // {name, pkg, rel}
for (const p of parsed) {
  p.lines.forEach((line, i) => {
    const m = line.match(/^private\s+(?:inline\s+)?(?:fun|val|var|const val)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)/);
    if (m) privateDecls.push({ name: m[1], pkg: p.pkg, rel: p.rel, line: i + 1, kind: 'private' });
    // 扩展函数：private fun Type.name(
    const e = line.match(/^private\s+fun\s+(?:<[^>]*>\s*)?[\w.]+\.(\w+)\s*\(/);
    if (e) privateDecls.push({ name: e[1], pkg: p.pkg, rel: p.rel, line: i + 1, kind: 'private-ext' });
  });
}
for (const d of privateDecls) {
  for (const p of parsed) {
    if (p.rel === d.rel) continue;
    if (p.pkg !== d.pkg) continue;
    // 同包不同文件引用 → 编译错误
    const re = new RegExp(`(?<![\\w.])${d.name}\\s*[({]`);
    const hit = p.lines.findIndex((l, i) =>
      re.test(l) &&
      !l.trim().startsWith('//') &&
      !l.trim().startsWith('*') &&
      !/^\s*(private|internal|public)\b/.test(l)
    );
    if (hit >= 0) {
      problems.push(`[A/private] ${p.rel}:${hit + 1} 使用了 ${d.name}，但它在 ${d.rel}:${d.line} 是 private（同包跨文件不可见）`);
    }
  }
}

// ---- B. 尾随 lambda 绑定错位 ----
// 收集本项目自定义的 @Composable/普通函数签名，取最后一个参数类型
const funcSigs = new Map(); // name -> {lastParamIsLambda, rel}
for (const p of parsed) {
  const text = p.text;
  const re = /fun\s+(\w+)\s*\(([\s\S]{0,600}?)\)\s*(?:\{|:|=)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const name = m[1];
    const params = m[2];
    // 用逗号切分顶层参数（粗略：忽略嵌套括号内的逗号）
    const parts = [];
    let depth = 0, cur = '';
    for (const ch of params) {
      if (ch === '(' || ch === '<') depth++;
      else if (ch === ')' || ch === '>') depth--;
      if (ch === ',' && depth === 0) { parts.push(cur); cur = ''; } else cur += ch;
    }
    if (cur.trim()) parts.push(cur);
    const last = parts[parts.length - 1] || '';
    if (!last.trim()) continue;
    const lastParamIsLambda = /->\s*Unit/.test(last) || /@Composable/.test(last);
    funcSigs.set(name, { lastParamIsLambda, rel: p.rel, last: last.trim().slice(0, 60) });
  }
}

for (const p of parsed) {
  p.lines.forEach((line, i) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*')) return;
    // 跳过函数声明本身（fun Name(...) { ），以及控制流语句
    if (/\bfun\s+<?[\w.<>]*\s*\w+\s*\(/.test(line)) return;
    if (/^\s*(if|when|for|while|catch|else|try|fun|class|object|interface|init)\b/.test(trimmed)) return;
    // 形如  Name(...)  {   即尾随 lambda
    const m = line.match(/(?<![\w.])([A-Z]\w+)\s*\([^()]*(?:\([^()]*\)[^()]*)*\)\s*\{\s*$/);
    if (!m) return;
    const name = m[1];
    const sig = funcSigs.get(name);
    if (!sig) return;             // 不是本项目自定义函数（可能是 Compose 标准 API）
    if (sig.lastParamIsLambda) return;
    problems.push(`[B/尾随lambda] ${p.rel}:${i + 1} 尾随 lambda 调用 ${name}()，但它最后一个参数不是 lambda（是 "${sig.last}"）`);
  });
}

// ---- C. List<String> 传给 List<DateKey> ----
// 找形如  fun foo(x: List<DateKey>) 的定义，再找调用点是否有 List<String> 来源
const dateKeyListFns = [];
for (const p of parsed) {
  for (const m of p.text.matchAll(/fun\s+(\w+)\s*\(([^)]*List<DateKey>[^)]*)\)/g)) {
    dateKeyListFns.push({ fn: m[1], rel: p.rel });
  }
}
const weekDaysFns = new Set(['weekDays']);
for (const p of parsed) {
  p.lines.forEach((line, i) => {
    for (const { fn } of dateKeyListFns) {
      const call = new RegExp(`\\.${fn}\\s*\\(([^)]*)\\)`);
      const m = line.match(call);
      if (!m) continue;
      const arg = m[1].trim();
      if (!arg) continue;
      // 如果实参来自 weekDays(...) 且没有 .map，风险很高
      if (arg.includes('weekDays(') && !arg.includes('.map')) {
        problems.push(`[C/类型] ${p.rel}:${i + 1} 调用 ${fn}(${arg})，但 ${fn} 需要 List<DateKey>，weekDays() 返回 List<String>`);
      }
    }
  });
}

console.log('=== 扫描结果 ===');
if (problems.length === 0) {
  console.log('未发现 A/B/C 三类问题残留');
} else {
  [...new Set(problems)].forEach(p => console.log('  ' + p));
}
console.log(`\n共 ${new Set(problems).size} 处`);
process.exit(new Set(problems).size > 0 ? 1 : 0);
