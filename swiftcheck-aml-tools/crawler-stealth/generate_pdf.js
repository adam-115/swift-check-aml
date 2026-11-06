const puppeteer = require('puppeteer-extra');
const StealthPlugin = require('puppeteer-extra-plugin-stealth');
puppeteer.use(StealthPlugin());
const path = require('path');
const fs = require('fs');

const EDGE_PATH = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function generate() {
  const htmlPath = path.resolve('D:/projects/swift-check-aml/documentation/Comparaison_SwiftCheck_vs_OpenSanctions.html');
  const pdfPath = path.resolve('D:/projects/swift-check-aml/documentation/Comparaison_SwiftCheck_vs_OpenSanctions_v2.pdf');
  const targetPdf = path.resolve('D:/projects/swift-check-aml/documentation/Comparaison_SwiftCheck_vs_OpenSanctions.pdf');

  console.log('Loading HTML:', htmlPath);

  const browser = await puppeteer.launch({
    executablePath: EDGE_PATH,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  const page = await browser.newPage();
  await page.goto('file:///' + htmlPath.replace(/\\/g, '/'), { waitUntil: 'networkidle0' });

  console.log('Rendering PDF...');
  await page.pdf({
    path: pdfPath,
    format: 'A4',
    printBackground: true,
    margin: {
      top: '12mm',
      bottom: '14mm',
      left: '12mm',
      right: '12mm'
    }
  });

  await browser.close();

  if (fs.existsSync(pdfPath)) {
    const stats = fs.statSync(pdfPath);
    console.log(`SUCCESS: PDF generated at ${pdfPath} (${stats.size} bytes)`);
    try {
      fs.copyFileSync(pdfPath, targetPdf);
      console.log('SUCCESS: Copied also to ' + targetPdf);
    } catch (e) {
      console.warn('Note: Comparaison_SwiftCheck_vs_OpenSanctions.pdf was locked by viewer; Comparaison_SwiftCheck_vs_OpenSanctions_v2.pdf generated.');
    }
  } else {
    console.error('ERROR: PDF was not generated.');
  }
}

generate().catch(err => {
  console.error('FAILED:', err);
  process.exit(1);
});

