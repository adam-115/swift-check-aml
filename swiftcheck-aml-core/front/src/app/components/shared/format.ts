/** Petites fonctions d'affichage partagées par les écrans d'ingestion et de test. */

/** Taille lisible : 24 312 004 → « 23,2 Mo ». */
export function fileSize(bytes?: number | null): string {
  if (bytes == null) {
    return '—';
  }
  const units = ['o', 'Ko', 'Mo', 'Go'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString('fr-FR', { maximumFractionDigits: unit === 0 ? 0 : 1 })} ${units[unit]}`;
}

/**
 * Nom court d'un fichier source à partir de son origine :
 * « https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=… » → « xmlFullSanctionsList_1_1 »,
 * « D:/sources/liste.xml » → « liste.xml ».
 */
export function sourceFileName(origin?: string | null): string {
  if (!origin) {
    return '—';
  }
  const path = origin.split('?')[0].replace(/\\/g, '/').replace(/\/+$/, '');
  const parts = path.split('/').filter(p => p && !/^https?:$/.test(p));
  const last = parts.pop() ?? origin;
  // « …/xmlFullSanctionsList_1_1/content » : le segment parlant est l'avant-dernier
  return ['content', 'download', 'file', 'export'].includes(last.toLowerCase()) && parts.length ? parts.pop()! : last;
}

/** Hôte d'une URL, ou « Fichier local » pour un chemin. */
export function sourceHost(origin?: string | null): string {
  if (!origin) {
    return '';
  }
  try {
    return /^https?:\/\//i.test(origin) ? new URL(origin).host : 'Fichier sur le serveur';
  } catch {
    return '';
  }
}

/** Durée lisible entre deux dates : « 42 s », « 2 min 05 s ». */
export function duration(start?: string | null, end?: string | null): string {
  if (!start || !end) {
    return '—';
  }
  return elapsed(new Date(end).getTime() - new Date(start).getTime());
}

/** Durée lisible en millisecondes. */
export function elapsed(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, '0')} s`;
}
