// Génère la baseline Liquibase à partir d'une base existante (schéma + données de référence), en changeSets XML lisibles.
//
// Usage : node generate-baseline.js <dossier de sortie> [conteneur MySQL] [base] [utilisateur] [mot de passe]
// Exemple : node generate-baseline.js ../../swiftcheck-aml-core/back/src/main/resources/db/changelog/baseline avo-mysql swift_check_2 adam adam
//
// Chaque changeSet porte une précondition « déjà présent → MARK_RAN » : le même changelog crée une base vide et se
// contente d'être marqué comme exécuté sur une base qui contient déjà ces données.
// Les identifiants sont écrits explicitement : ils sont les mêmes dans toutes les bases.
// Les chemins propres à une machine deviennent des paramètres Liquibase (${yenteDataFolder}…), fournis par
// spring.liquibase.parameters.* (variables d'environnement).

const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const [, , outDir, container = 'avo-mysql', db = 'swift_check_2', user = 'adam', password = 'adam'] = process.argv;
if (!outDir) {
  console.error('Usage : node generate-baseline.js <dossier de sortie> [conteneur] [base] [utilisateur] [mot de passe]');
  process.exit(1);
}

const AUTHOR = 'swiftcheck-baseline';
const REFERENCE_TABLES = ['country', 'pep_category', 'pep_function', 'parser', 'pep_function_parser', 'application_config', 'app_user', 'app_user_roles'];

function mysql(sql) {
  return execFileSync('docker', ['exec', container, 'mysql', `-u${user}`, `-p${password}`, db, '-N', '--raw',
    '--default-character-set=utf8mb4', '-e', sql], { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] });
}

function columns(table) {
  return mysql(`SELECT column_name FROM information_schema.columns WHERE table_schema='${db}' AND table_name='${table}' ORDER BY ordinal_position`)
    .split('\n').filter(Boolean);
}

function rows(table, orderBy) {
  const cols = columns(table);
  const obj = cols.map(c => `'${c}', \`${c}\``).join(', ');
  // JSON_OBJECT trie les clés : on remet les colonnes dans l'ordre de la table, pour des fichiers lisibles
  return mysql(`SELECT JSON_OBJECT(${obj}) FROM \`${table}\` ORDER BY ${orderBy}`).split('\n').filter(Boolean)
    .map(l => JSON.parse(l)).map(o => Object.fromEntries(cols.map(c => [c, o[c]])));
}

// ------------------------------------------------------------------ XML

const header = `<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
`;
const footer = '</databaseChangeLog>\n';

function cdata(s) {
  return '<![CDATA[' + String(s).replace(/]]>/g, ']]]]><![CDATA[>') + ']]>';
}

function attr(s) {
  return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Une ligne en <insert> : valeurs nulles omises, textes en CDATA, nombres et booléens en attributs. */
function insert(table, row, transform = (c, v) => v) {
  let xml = `        <insert tableName="${table}">\n`;
  for (const [c, raw] of Object.entries(row)) {
    const v = transform(c, raw);
    if (v === null || v === undefined) {
      continue;
    }
    if (typeof v === 'number') {
      xml += `            <column name="${c}" valueNumeric="${v}"/>\n`;
    } else if (typeof v === 'boolean') {
      xml += `            <column name="${c}" valueBoolean="${v}"/>\n`;
    } else if (v.length < 120 && !/[\n<>&"]/.test(v)) {
      xml += `            <column name="${c}" value="${attr(v)}"/>\n`;
    } else {
      xml += `            <column name="${c}">${cdata(v)}</column>\n`;
    }
  }
  return xml + '        </insert>\n';
}

function changeSet(id, comment, precondition, body) {
  return `
    <changeSet id="${id}" author="${AUTHOR}">
        <preConditions onFail="MARK_RAN">
${precondition}
        </preConditions>
        <comment>${attr(comment)}</comment>
${body}    </changeSet>
`;
}

function noRows(sql) {
  return `            <sqlCheck expectedResult="0">${attr(sql)}</sqlCheck>`;
}

function write(file, body) {
  fs.writeFileSync(path.join(outDir, file), header + body + footer);
  console.log('écrit', file);
}

// ------------------------------------------------------------------ schéma

fs.mkdirSync(outDir, { recursive: true });
const tables = mysql(`SELECT table_name FROM information_schema.tables WHERE table_schema='${db}' AND table_type='BASE TABLE'
  AND table_name NOT IN ('DATABASECHANGELOG','DATABASECHANGELOGLOCK') ORDER BY table_name`).split('\n').filter(Boolean);

let schema = '';
const foreignKeys = [];
for (const t of tables) {
  const ddl = mysql(`SHOW CREATE TABLE \`${t}\``).split('\t').slice(1).join('\t').trim();
  const lines = ddl.split('\n');
  const kept = [];
  for (const l of lines) {
    if (/^\s*CONSTRAINT `[^`]+` FOREIGN KEY/.test(l)) {
      foreignKeys.push(`ALTER TABLE \`${t}\` ADD ${l.trim().replace(/,$/, '')};`);
    } else {
      kept.push(l);
    }
  }
  // virgule finale avant la parenthèse fermante, une fois les clés étrangères retirées
  let create = kept.join('\n').replace(/,\n\)/, '\n)').replace(/ AUTO_INCREMENT=\d+/, '');
  schema += changeSet(`baseline-schema-${t}`, `Table ${t}`, `            <not><tableExists tableName="${t}"/></not>`,
    `        <sql>${cdata(create)}</sql>\n`);
}
schema += changeSet('baseline-schema-foreign-keys', 'Clés étrangères (après toutes les tables)',
  noRows(`SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()`),
  foreignKeys.map(fk => `        <sql>${cdata(fk)}</sql>\n`).join(''));
write('b000-schema.xml', schema);

// ------------------------------------------------------------------ pays

let countries = '';
for (const r of rows('country', 'iso')) {
  countries += changeSet(`baseline-country-${r.iso}`, `Pays ${r.iso} (${r.name})`,
    noRows(`SELECT COUNT(*) FROM country WHERE iso = '${r.iso}'`), insert('country', r));
}
write('b010-countries.xml', countries);

// ------------------------------------------------------------------ catégories et fonctions PEP, par pays

const categories = rows('pep_category', 'id');
const functions = rows('pep_function', 'id');
const isos = [...new Set(categories.map(c => c.country_iso))];
let pep = '';
for (const iso of isos) {
  const cats = categories.filter(c => c.country_iso === iso);
  const catIds = new Set(cats.map(c => c.id));
  const funcs = functions.filter(f => catIds.has(f.category_id));
  pep += changeSet(`baseline-pep-${iso}`, `Catégories (${cats.length}) et fonctions PEP (${funcs.length}) : ${iso}`,
    noRows(`SELECT COUNT(*) FROM pep_category WHERE country_iso = '${iso}'`),
    cats.map(c => insert('pep_category', c)).join('') + funcs.map(f => insert('pep_function', f)).join(''));
}
write('b020-pep-categories-functions.xml', pep);

// ------------------------------------------------------------------ parsers et leurs liens, par préfixe de dataset

const parsers = rows('parser', 'id');
const links = rows('pep_function_parser', 'parser_id, function_id');
const parserTransform = (c, v) => {
  if (c === 'plugin_url') {
    return null; // les plugins sont chargés depuis pf4j.plugins.dir : aucun chemin de machine en base
  }
  if (c === 'parameters' && typeof v === 'string') {
    return v.replace(/"cacheDir":\s*"[^"]*"/, '"cacheDir": "${jorfCacheDir}"');
  }
  return v;
};
const groups = {};
for (const p of parsers) {
  const prefix = p.dataset_name.split('_')[0];
  (groups[prefix] = groups[prefix] || []).push(p);
}
for (const [prefix, list] of Object.entries(groups).sort()) {
  const ids = new Set(list.map(p => p.id));
  const own = links.filter(l => ids.has(l.parser_id));
  const datasets = list.map(p => `'${p.dataset_name}'`).join(', ');
  const body = changeSet(`baseline-parsers-${prefix}`, `Parsers « ${prefix}_* » (${list.length}) et leurs liens aux fonctions PEP (${own.length})`,
    noRows(`SELECT COUNT(*) FROM parser WHERE dataset_name IN (${datasets})`),
    list.map(p => insert('parser', p, parserTransform)).join('') + own.map(l => insert('pep_function_parser', l)).join(''));
  write(`b030-parsers-${prefix}.xml`, body);
}

// ------------------------------------------------------------------ configuration et comptes

const config = rows('application_config', 'id').map(r => insert('application_config', r, (c, v) => ({
  yente_url: '${yenteUrl}',
  yente_update_token: '${yenteUpdateToken}',
  yente_data_folder: '${yenteDataFolder}',
  yente_manifest_path: '${yenteManifestPath}',
  archive_folder: '${archiveFolder}',
  work_folder: '${workFolder}'
})[c] ?? v)).join('');
const users = rows('app_user', 'id').map(r => insert('app_user', r, (c, v) => (c === 'last_login_date' || c === 'keycloak_id' ? null : v))).join('')
  + rows('app_user_roles', 'user_id').map(r => insert('app_user_roles', r)).join('');
write('b040-application.xml',
  changeSet('baseline-application-config', 'Configuration de l\'application (chemins fournis par les paramètres Liquibase)',
    noRows('SELECT COUNT(*) FROM application_config'), config)
  + changeSet('baseline-users', 'Comptes applicatifs initiaux (rôles ; l\'authentification passe par Keycloak)',
    noRows('SELECT COUNT(*) FROM app_user'), users));

// ------------------------------------------------------------------ index

const files = fs.readdirSync(outDir).filter(f => /^b\d{3}-.*\.xml$/.test(f)).sort();
fs.writeFileSync(path.join(outDir, 'baseline.xml'), header
  + '    <!-- Baseline générée par swiftcheck-aml-tools/liquibase-baseline/generate-baseline.js : ne pas modifier à la main,\n'
  + '         ajouter les changements suivants dans db/changelog/changes (après la baseline). -->\n'
  + files.map(f => `    <include file="db/changelog/baseline/${f}"/>\n`).join('') + footer);
console.log(`baseline : ${tables.length} tables, ${countries.split('<changeSet').length - 1} pays, ${isos.length} pays PEP, ${parsers.length} parsers, ${links.length} liens`);
