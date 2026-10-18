// kotlin_import_check.mjs
// 检查「使用了某个大写开头的类型名，但既没 import、也不在本文件/本包/项目里定义」的情况。
// 这类问题编译器报 Unresolved reference，是最基础也最容易漏的错（例如漏了 android.content.Intent）。
// 只做保守判断：宁可漏报，不制造大量误报。
//
// 已知的「误报」类别（人工确认后可忽略）：
//   1. Kotlin/JVM 默认导入：kotlin.*、kotlin.jvm.*、kotlin.text.*、kotlin.collections.*、
//      java.lang.* —— 例如 Volatile / JvmInline / Charsets / Class / Math / LinkedHashMap；
//   2. 继承作用域里的父类嵌套类 —— 例如 AppDatabase : RoomDatabase() 内部可以直接写
//      Callback()，等价于 RoomDatabase.Callback。
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

// Kotlin 内置 / Java 常见类型，不需要 import
const BUILTIN = new Set([
  'String', 'Int', 'Long', 'Short', 'Byte', 'Double', 'Float', 'Boolean', 'Char', 'Unit', 'Any',
  'Nothing', 'List', 'MutableList', 'Map', 'MutableMap', 'Set', 'MutableSet', 'Pair', 'Triple',
  'Array', 'IntArray', 'Sequence', 'Result', 'Exception', 'Throwable', 'RuntimeException',
  'IllegalArgumentException', 'IllegalStateException', 'UnsupportedOperationException',
  'System', 'Integer', 'Long', 'StringBuilder', 'Comparable', 'Iterable', 'Collection',
  'Override', 'Suppress', 'JvmStatic', 'JvmField', 'JvmTarget', 'Deprecated', 'OptIn',
  'Composable', 'T', 'R', 'K', 'V', 'E', 'C', 'B', 'A',
]);

const parsed = files.map(f => {
  const text = readFileSync(f, 'utf8');
  const pkg = (text.match(/^package\s+([\w.]+)/m) || [])[1] || '';
  return { file: f, rel: relative(ROOT, f), text, pkg, lines: text.split('\n') };
});

// 全项目的顶层声明名（按包归类）
const declaredByPkg = new Map();
for (const p of parsed) {
  const names = new Set();
  for (const m of p.text.matchAll(/^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |abstract |open |sealed |data |value |enum |annotation )*(?:class|object|interface|annotation class)\s+(\w+)/gm)) {
    names.add(m[1]);
  }
  for (const m of p.text.matchAll(/^(?:public |internal |private )?(?:suspend |inline |operator |infix )*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(/gm)) {
    names.add(m[1]);
  }
  for (const m of p.text.matchAll(/^(?:public |internal |private )?(?:const )?val\s+(\w+)/gm)) {
    names.add(m[1]);
  }
  if (!declaredByPkg.has(p.pkg)) declaredByPkg.set(p.pkg, new Set());
  for (const n of names) declaredByPkg.get(p.pkg).add(n);
}

let problems = 0;
for (const p of parsed) {
  const imports = new Set();
  let hasWildcardFromKotlin = false;
  for (const m of p.text.matchAll(/^import\s+([\w.]+)(?:\.\*)?(?:\s+as\s+(\w+))?\s*$/gm)) {
    const fq = m[1];
    const alias = m[2];
    const simple = alias || fq.split('.').pop();
    imports.add(simple);
  }
  // 本文件内声明的类型
  const local = new Set();
  for (const m of p.text.matchAll(/(?:class|object|interface)\s+(\w+)/g)) local.add(m[1]);
  // 泛型参数
  for (const m of p.text.matchAll(/<\s*([A-Z]\w*)(?:\s*[:,][^>]*)?>/g)) local.add(m[1]);
  // 枚举成员/常量（全大写）
  const samePkg = declaredByPkg.get(p.pkg) || new Set();

  // 使用位置：类型标注 (: Name)、构造调用 Name(、静态访问 Name.
  const used = new Map(); // name -> firstLine
  p.lines.forEach((line, i) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('import ') || trimmed.startsWith('package ')) return;
    // 去掉字符串与字符字面量，避免误判
    const clean = line.replace(/"(?:\\.|[^"\\])*"/g, '""').replace(/'(?:\\.|[^'\\])'|'(?:\\u[0-9a-fA-F]{4}|\\.)'/g, "''");
    for (const m of clean.matchAll(/(?<![\w.$])([A-Z][A-Za-z0-9_]*)\b(?=\s*[.(<:?]|\s*$)/g)) {
      const name = m[1];
      if (!used.has(name)) used.set(name, i + 1);
    }
  });

  for (const [name, line] of used) {
    if (BUILTIN.has(name)) continue;
    if (/^[A-Z0-9_]+$/.test(name)) continue;        // 全大写常量
    if (imports.has(name)) continue;
    if (local.has(name)) continue;
    if (samePkg.has(name)) continue;
    // 常见前缀的类可能来自同包未捕获的情况，这里只报「明显像 Android/Java 类」的
    console.log(`[未导入?] ${p.rel}:${line}  ${name}`);
    problems++;
  }
}

console.log('\n========================================');
console.log(`扫描 ${parsed.length} 个文件，可疑未导入符号 ${problems} 处（需人工确认）`);
