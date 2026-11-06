const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());

const EDGE_PATH = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

const URLS = [
  { name: 'FR APE', url: 'https://www.economie.gouv.fr/agence-participations-etat/comprendre-lape/les-entreprises-de-lape' },
  { name: 'FR InfoGouv', url: 'https://www.info.gouv.fr/' },
  { name: 'BE Premier', url: 'https://www.premier.be/' },
  { name: 'BE Eurocontrol', url: 'https://www.eurocontrol.int/about-us' },
  { name: 'BE Wallonie', url: 'https://gouvernement.wallonie.be/home/les-ministres.html' },
  { name: 'BE Bruxelles', url: 'https://parlement.brussels/' },
  { name: 'DE BMF', url: 'https://www.bundesfinanzministerium.de/' },
  { name: 'ES TC', url: 'https://www.tribunalconstitucional.es/' },
  { name: 'ES CNMV', url: 'https://www.cnmv.es/' },
  { name: 'ES AsambMadrid', url: 'https://www.asambleamadrid.es/' },
  { name: 'PT Parlamento', url: 'https://www.parlamento.pt/' },
  { name: 'PT BancoPort', url: 'https://www.bportugal.pt/' },
  { name: 'AT IAEA', url: 'https://www.iaea.org/' },
  { name: 'IT CorteCost', url: 'https://www.cortecostituzionale.it/' },
  { name: 'IT Consob', url: 'https://www.consob.it/' },
  { name: 'IT Cassazione', url: 'https://www.cortedicassazione.it/' },
  { name: 'IT CorteConti', url: 'https://www.corteconti.it/' },
  { name: 'IT CSM', url: 'https://www.csm.it/' },
  { name: 'IT Esteri', url: 'https://www.esteri.it/' },
  { name: 'IT IFAD', url: 'https://www.ifad.org/en/leadership' },
  { name: 'IMF Leaders', url: 'https://www.imf.org/en/About/senior-officials' }
];

async function main() {
  const browser = await puppeteer.launch({
    executablePath: EDGE_PATH,
    headless: 'new',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--disable-blink-features=AutomationControlled',
      '--window-size=1920,1080'
    ]
  });

  console.log('=== TESTING URLS WITH PUPPETEER STEALTH ===\n');

  for (const item of URLS) {
    let page = null;
    const start = Date.now();
    try {
      page = await browser.newPage();
      await page.setViewport({ width: 1920, height: 1080 });
      await page.setExtraHTTPHeaders({
        'Accept-Language': 'fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,es;q=0.6,it;q=0.5,de;q=0.5'
      });

      const response = await page.goto(item.url, { waitUntil: 'domcontentloaded', timeout: 25000 });
      
      // wait for challenges if any
      for (let i = 0; i < 5; i++) {
        const title = await page.title();
        if (!title.includes('Just a moment') && !title.includes('Security Check') && !title.includes('Un instant')) {
          break;
        }
        await new Promise(r => setTimeout(r, 1000));
      }

      const status = response ? response.status() : 'N/A';
      const title = await page.title();
      const length = (await page.content()).length;
      const ms = Date.now() - start;
      console.log(`[OK ${status}] ${item.name.padEnd(16)} | ${ms}ms | Len: ${length.toString().padEnd(7)} | Title: ${title.slice(0, 45)}`);
    } catch (err) {
      const ms = Date.now() - start;
      console.log(`[ERR]    ${item.name.padEnd(16)} | ${ms}ms | Error: ${err.message}`);
    } finally {
      if (page) await page.close().catch(() => {});
    }
  }

  await browser.close();
}

main().catch(console.error);

