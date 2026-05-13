// AEM Postmortem — manage incident post-mortems for AEM Edge Delivery Services
// Works with the aem-status repo at /workspace/aem-status/

const INCIDENTS_DIR = '/workspace/aem-status/incidents/md';
const INDEX_FILE = '/workspace/aem-status/incidents/index.json';
const TEMPLATE_SHORT = INCIDENTS_DIR + '/incident-template-short.markdown';
const TEMPLATE_LONG = INCIDENTS_DIR + '/incident-template-long.markdown';

// --- Helpers ---

function generateId(length) {
  return Math.random().toString(36).substring(2, 2 + length);
}

function parseArgs(args) {
  const flags = {};
  const positional = [];
  for (const arg of args) {
    if (arg.startsWith('--')) {
      const eq = arg.indexOf('=');
      if (eq !== -1) {
        flags[arg.substring(2, eq)] = arg.substring(eq + 1);
      } else {
        flags[arg.substring(2)] = true;
      }
    } else {
      positional.push(arg);
    }
  }
  return { flags, positional };
}

function normalizeIncidentId(id) {
  if (!id) return null;
  // Accept "AEM-xxxx" or just "xxxx"
  if (id.toUpperCase().startsWith('AEM-')) {
    return id;
  }
  return 'AEM-' + id;
}

function incidentFilePath(id) {
  // Try with the given ID first, then with AEM- prefix
  return INCIDENTS_DIR + '/' + id + '.markdown';
}

function parseFrontmatter(content) {
  const match = content.match(/^---\n([\s\S]*?)\n---/);
  if (!match) return {};
  const fm = {};
  const lines = match[1].split('\n');
  for (const line of lines) {
    const colon = line.indexOf(':');
    if (colon === -1) continue;
    const key = line.substring(0, colon).trim();
    let val = line.substring(colon + 1).trim();
    // Strip surrounding quotes
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
      val = val.substring(1, val.length - 1);
    }
    fm[key] = val;
  }
  return fm;
}

function impactFromRate(rate) {
  const r = parseFloat(rate);
  if (isNaN(r)) return 'unknown';
  if (r < 0.005) return 'none';
  if (r < 0.05) return 'minor';
  if (r < 0.1) return 'major';
  return 'critical';
}

function formatDuration(startStr, endStr) {
  const start = new Date(startStr);
  const end = new Date(endStr);
  if (isNaN(start.getTime()) || isNaN(end.getTime())) return 'unknown';
  const diffMs = end.getTime() - start.getTime();
  const hours = Math.floor(diffMs / 3600000);
  const minutes = Math.floor((diffMs % 3600000) / 60000);
  if (hours === 0) return minutes + 'm';
  return hours + 'h ' + minutes + 'm';
}

async function readIndex() {
  try {
    const raw = await fs.readFile(INDEX_FILE, 'utf8');
    return JSON.parse(raw);
  } catch (e) {
    console.error('Failed to read index.json: ' + e.message);
    return [];
  }
}

async function findIncidentFile(id) {
  // Try exact ID as filename
  let path = INCIDENTS_DIR + '/' + id + '.markdown';
  if (await fs.exists(path)) return path;

  // Try with AEM- prefix
  if (!id.toUpperCase().startsWith('AEM-')) {
    path = INCIDENTS_DIR + '/AEM-' + id + '.markdown';
    if (await fs.exists(path)) return path;
  }

  // Try without AEM- prefix
  if (id.toUpperCase().startsWith('AEM-')) {
    path = INCIDENTS_DIR + '/' + id.substring(4) + '.markdown';
    if (await fs.exists(path)) return path;
  }

  return null;
}

// --- Commands ---

async function cmdNew(args) {
  const { flags } = parseArgs(args);
  const template = flags.template || 'short';
  const title = flags.title || '';

  // Generate incident ID
  const id = generateId(8);
  const code = 'AEM-' + id;
  const filePath = INCIDENTS_DIR + '/' + code + '.markdown';

  // Read template
  const templatePath = template === 'long' ? TEMPLATE_LONG : TEMPLATE_SHORT;
  if (!(await fs.exists(templatePath))) {
    console.error('Template not found: ' + templatePath);
    process.exit(1);
  }

  let content = await fs.readFile(templatePath, 'utf8');

  // Set title if provided
  if (title) {
    content = content.replace(
      /^# \[.*\]$/m,
      '# ' + title
    );
  }

  // Set postmortem-completed to now
  const now = new Date().toISOString();
  content = content.replace(
    /postmortem-completed:.*$/m,
    'postmortem-completed: "' + now + '"'
  );

  await fs.writeFile(filePath, content);

  console.log('Created postmortem: ' + code);
  console.log('  File: ' + filePath);
  console.log('  Template: ' + template);
  if (title) console.log('  Title: ' + title);
  console.log('');
  console.log('Next steps:');
  console.log('  1. Fill in the frontmatter (start-time, end-time, error-rate, impacted-service)');
  console.log('  2. Write the postmortem sections');
  console.log('  3. Run: postmortem classify ' + code);
  console.log('  4. Run: postmortem branch ' + code);
}

async function cmdList(args) {
  const { flags } = parseArgs(args);
  const limit = parseInt(flags.limit) || 10;

  const index = await readIndex();
  const items = index.slice(0, limit);

  if (items.length === 0) {
    console.log('No incidents found.');
    return;
  }

  console.log('Recent incidents (' + items.length + ' of ' + index.length + '):');
  console.log('');

  for (const item of items) {
    const impact = (item.impact || 'unknown').toUpperCase().padEnd(8);
    const service = (item.impactedService || '-').padEnd(12);
    const date = item.startTime
      ? new Date(item.startTime).toISOString().substring(0, 10)
      : item.incidentUpdated
        ? new Date(item.incidentUpdated).toISOString().substring(0, 10)
        : '-';

    console.log('  ' + item.code.padEnd(18) + ' ' + impact + ' ' + service + ' ' + date);
    console.log('    ' + item.name);
    console.log('');
  }
}

async function cmdGet(id) {
  if (!id) {
    console.error('Usage: postmortem get <incident-id>');
    console.error('  Accepts AEM-xxxx or just xxxx');
    process.exit(1);
  }

  const filePath = await findIncidentFile(id);
  if (!filePath) {
    console.error('Incident file not found for: ' + id);
    console.error('Searched in: ' + INCIDENTS_DIR);
    process.exit(1);
  }

  const content = await fs.readFile(filePath, 'utf8');
  const fm = parseFrontmatter(content);

  console.log('File: ' + filePath);
  console.log('');

  if (fm['start-time'] && fm['end-time']) {
    console.log('Duration: ' + formatDuration(fm['start-time'], fm['end-time']));
  }
  if (fm['error-rate']) {
    const rate = parseFloat(fm['error-rate']);
    if (!isNaN(rate)) {
      console.log('Error rate: ' + (rate * 100).toFixed(4) + '% (' + fm['error-rate'] + ')');
      console.log('Impact: ' + impactFromRate(rate) + (fm.impact ? ' (declared: ' + fm.impact + ')' : ''));
    }
  }
  console.log('');
  console.log(content);
}

async function cmdClassify(id) {
  if (!id) {
    console.error('Usage: postmortem classify <incident-id>');
    process.exit(1);
  }

  const filePath = await findIncidentFile(id);
  if (!filePath) {
    console.error('Incident file not found for: ' + id);
    process.exit(1);
  }

  const content = await fs.readFile(filePath, 'utf8');
  const fm = parseFrontmatter(content);
  const bodyLower = content.toLowerCase();

  // Suggest affectedComponents
  let affectedComponents = null;
  if (fm['impacted-service'] === 'delivery') affectedComponents = ['delivery'];
  else if (fm['impacted-service'] === 'publishing') affectedComponents = ['publishing'];

  // Suggest internalServices
  const internalMap = {
    'admin-api': ['admin api', 'admin-api', 'admin service'],
    'forms': ['forms'],
    'code-sync': ['code sync', 'code-sync', 'codesync'],
    'rum': ['rum ', 'real user monitoring'],
    'indexing': ['index', 'indexing'],
    'logging': ['logging', 'coralogix', 'log '],
    'dns': ['dns'],
    'sidekick': ['sidekick'],
    'media': ['media bus', 'media-bus', 'media service']
  };
  const internalServices = [];
  for (const [key, keywords] of Object.entries(internalMap)) {
    for (const kw of keywords) {
      if (bodyLower.includes(kw)) {
        internalServices.push(key);
        break;
      }
    }
  }

  // Suggest externalVendors
  const vendorMap = {
    'cloudflare': ['cloudflare'],
    'aws': ['aws', 'amazon'],
    'fastly': ['fastly'],
    'github': ['github'],
    'microsoft': ['microsoft', 'azure'],
    'unpkg': ['unpkg'],
    'zscaler': ['zscaler'],
    'webpack': ['webpack']
  };
  const externalVendors = [];
  for (const [key, keywords] of Object.entries(vendorMap)) {
    for (const kw of keywords) {
      if (bodyLower.includes(kw)) {
        externalVendors.push(key);
        break;
      }
    }
  }

  // Suggest rootCause — order matters: more specific patterns first
  let rootCause = 'unknown';
  const rcKeywords = {
    'third-party-outage': ['third.party', '3rd.party', 'outage at', 'provider outage', 'cloudflare outage', 'cloudflare.*incident'],
    'network-issue': ['network connectiv', 'network issue', 'connectivity issue', 'packet loss', 'network.*error', 'latency spike'],
    'dns-issue': ['dns resolution', 'dns outage', 'dns issue', 'dns fail'],
    'deployment-issue': ['deploy', 'regression', 'rollback', 'release'],
    'configuration-change': ['configuration change', 'config change', 'misconfigur', 'permission.*removed', 'permission.*change'],
    'resource-limits': ['rate limit', 'resource limit', 'capacity', 'exhausted', 'quota', '413', 'too large'],
    'credential-issue': ['credential', 'certificate expir', 'token expir', 'authentication fail', 'permission.*expir'],
    'dependency-issue': ['dependency', 'upstream.*fail', 'downstream.*fail']
  };
  for (const [cause, patterns] of Object.entries(rcKeywords)) {
    for (const p of patterns) {
      const re = new RegExp(p, 'i');
      if (re.test(content)) {
        rootCause = cause;
        break;
      }
    }
    if (rootCause !== 'unknown') break;
  }

  console.log('Classification suggestions for: ' + id);
  console.log('');
  console.log(JSON.stringify({
    affectedComponents: affectedComponents,
    internalServices: internalServices.length > 0 ? internalServices : null,
    externalVendors: externalVendors.length > 0 ? externalVendors : null,
    rootCause: rootCause
  }, null, 2));
  console.log('');
  console.log('Valid values reference:');
  console.log('  affectedComponents: delivery, publishing');
  console.log('  internalServices:   admin-api, forms, code-sync, rum, indexing, logging, dns, sidekick, media');
  console.log('  externalVendors:    cloudflare, aws, fastly, github, microsoft, unpkg, zscaler, webpack');
  console.log('  rootCause:          third-party-outage, configuration-change, deployment-issue,');
  console.log('                      resource-limits, credential-issue, dns-issue, network-issue,');
  console.log('                      dependency-issue, unknown');
}

async function cmdImpact(rateStr) {
  if (!rateStr) {
    console.error('Usage: postmortem impact <error-rate>');
    console.error('  Rate as decimal (0.034) or percentage (3.4)');
    process.exit(1);
  }

  let rate = parseFloat(rateStr);
  if (isNaN(rate)) {
    console.error('Invalid rate: ' + rateStr);
    process.exit(1);
  }

  // If rate > 1, assume it was given as a percentage
  if (rate > 1) {
    console.log('Interpreting ' + rateStr + ' as ' + rate + '% (' + (rate / 100) + ' decimal)');
    rate = rate / 100;
  }

  const impact = impactFromRate(rate);
  const pct = (rate * 100).toFixed(4) + '%';
  const templateRec = (impact === 'none' || impact === 'minor') ? 'short' : 'long';

  console.log('Error rate: ' + pct + ' (' + rate + ')');
  console.log('Impact:     ' + impact);
  console.log('Template:   ' + templateRec + ' (recommended)');
  console.log('');
  console.log('Thresholds:');
  console.log('  none     < 0.5%   (< 0.005)');
  console.log('  minor    < 5%     (< 0.05)');
  console.log('  major    < 10%    (< 0.1)');
  console.log('  critical >= 10%   (>= 0.1)');
}

async function cmdBranch(id) {
  if (!id) {
    console.error('Usage: postmortem branch <incident-id>');
    process.exit(1);
  }

  const normalized = normalizeIncidentId(id);
  const branchName = normalized.toLowerCase();
  const incidentId = normalized;

  // Try to read the file for the title
  let title = '';
  const filePath = await findIncidentFile(id);
  if (filePath) {
    const content = await fs.readFile(filePath, 'utf8');
    const titleMatch = content.match(/^# (.+)$/m);
    if (titleMatch) {
      title = titleMatch[1];
    }
  }

  const commitMsg = title ? 'feat: ' + title : 'feat: Postmortem for ' + incidentId;
  const previewUrl = 'https://' + branchName + '--aem-status--adobe.aem.page/details.html?incident=' + incidentId;

  console.log('Git workflow for ' + incidentId + ':');
  console.log('');
  console.log('1. Create branch:');
  console.log('   cd /workspace/aem-status');
  console.log('   git checkout -b ' + branchName);
  console.log('');
  console.log('2. Stage and commit:');
  console.log('   git add incidents/md/' + incidentId + '.markdown');
  console.log('   git commit -m "' + commitMsg + '"');
  console.log('');
  console.log('3. Push and create PR:');
  console.log('   git push origin ' + branchName);
  console.log('');
  console.log('4. PR description:');
  console.log('   ---');
  console.log('   Postmortem for #' + incidentId);
  console.log('');
  console.log('   URL: ' + previewUrl);
  console.log('   ---');
  console.log('');
  console.log('5. Preview URL (available after push):');
  console.log('   ' + previewUrl);
}

async function cmdMonday(args) {
  const { flags } = parseArgs(args);
  const rangeStr = flags.range || '7d';
  const rangeMatch = rangeStr.match(/^(\d+)d$/);
  const days = rangeMatch ? parseInt(rangeMatch[1]) : 7;
  const cutoff = new Date(Date.now() - days * 24 * 60 * 60 * 1000);

  const index = await readIndex();

  // Filter to incidents within range
  const recent = index.filter(function(item) {
    const dateStr = item.startTime || item.incidentUpdated;
    if (!dateStr) return false;
    return new Date(dateStr) >= cutoff;
  });

  if (recent.length === 0) {
    console.log('No incidents in the last ' + days + ' days.');
    return;
  }

  console.log('AEM EDS Incidents — last ' + days + ' days (' + recent.length + ' total)');
  console.log('='.repeat(60));
  console.log('');

  // Group by impact
  const byImpact = { critical: [], major: [], minor: [], none: [] };
  for (const item of recent) {
    const bucket = byImpact[item.impact] || byImpact.none;
    bucket.push(item);
  }

  for (const level of ['critical', 'major', 'minor', 'none']) {
    const items = byImpact[level];
    if (items.length === 0) continue;

    console.log(level.toUpperCase() + ' (' + items.length + ')');
    console.log('-'.repeat(40));

    for (const item of items) {
      const date = item.startTime
        ? new Date(item.startTime).toISOString().substring(0, 10)
        : '-';
      const service = item.impactedService || '-';
      const rate = item.errorRate ? (parseFloat(item.errorRate) * 100).toFixed(3) + '%' : '-';
      const rc = item.rootCause || 'unknown';
      const vendors = item.externalVendors ? item.externalVendors.join(', ') : '-';

      console.log('  ' + item.code + '  ' + date + '  ' + service);
      console.log('    ' + item.name);
      console.log('    error-rate: ' + rate + '  root-cause: ' + rc + '  vendors: ' + vendors);
      console.log('');
    }
  }

  // Summary stats
  const deliveryCount = recent.filter(function(i) { return i.impactedService === 'delivery'; }).length;
  const publishingCount = recent.filter(function(i) { return i.impactedService === 'publishing'; }).length;

  console.log('Summary:');
  console.log('  Total:      ' + recent.length);
  console.log('  Delivery:   ' + deliveryCount);
  console.log('  Publishing: ' + publishingCount);
  console.log('  Critical:   ' + byImpact.critical.length);
  console.log('  Major:      ' + byImpact.major.length);
  console.log('  Minor:      ' + byImpact.minor.length);
  console.log('  None:       ' + byImpact.none.length);
}

function showHelp() {
  console.log('AEM Postmortem — incident post-mortem management\n');
  console.log('Commands:');
  console.log('  new [--title="..."] [--template=short|long]');
  console.log('                               Create a new postmortem file');
  console.log('  list [--limit=10]            List recent incidents from index.json');
  console.log('  get <incident-id>            Read and display a postmortem');
  console.log('  classify <incident-id>       Suggest auto-classification fields');
  console.log('  impact <error-rate>          Calculate impact level from error rate');
  console.log('  branch <incident-id>         Show git/PR workflow commands');
  console.log('  monday [--range=7d]          Monday stand-up incident summary\n');
  console.log('Incident IDs: accepts AEM-xxxx or just xxxx');
  console.log('');
  console.log('Examples:');
  console.log('  postmortem new --title="CDN Latency Spike"');
  console.log('  postmortem new --title="Global Outage" --template=long');
  console.log('  postmortem list --limit=5');
  console.log('  postmortem get AEM-t58nxd8r');
  console.log('  postmortem get t58nxd8r');
  console.log('  postmortem classify AEM-t58nxd8r');
  console.log('  postmortem impact 0.034');
  console.log('  postmortem impact 3.4');
  console.log('  postmortem branch AEM-t58nxd8r');
  console.log('  postmortem monday --range=14d');
}

// --- Main ---
const rawArgs = process.argv.slice(2);
const cmd = rawArgs[0];
const args = rawArgs.slice(1);

if (!cmd || cmd === 'help' || cmd === '--help') {
  showHelp();
  process.exit(cmd ? 0 : 1);
}

switch (cmd) {
  case 'new':
    await cmdNew(args);
    break;
  case 'list':
    await cmdList(args);
    break;
  case 'get':
    await cmdGet(args[0]);
    break;
  case 'classify':
    await cmdClassify(args[0]);
    break;
  case 'impact':
    await cmdImpact(args[0]);
    break;
  case 'branch':
    await cmdBranch(args[0]);
    break;
  case 'monday':
    await cmdMonday(args);
    break;
  default:
    console.error('Unknown command: ' + cmd);
    showHelp();
    process.exit(1);
}
