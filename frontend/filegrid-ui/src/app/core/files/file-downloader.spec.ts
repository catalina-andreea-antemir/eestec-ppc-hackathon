import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiError } from '../api/api-error';
import { FileDownloader, isLinkExpired, LinkExpiredError, NATIVE_DOWNLOAD } from './file-downloader';
import { describeFileError } from './file-errors';
import { DownloadLink } from './files.models';

const FILE = { id: 'f-1', originalName: 'drona-linie-110kV-tronson-7.mp4' };
const NOW = Date.parse('2026-11-14T10:00:00Z');
const inMinutes = (m: number) => new Date(NOW + m * 60_000).toISOString();

describe('FileDownloader', () => {
  let downloader: FileDownloader;
  let backend: HttpTestingController;
  let opened: { url: string; fileName: string }[];

  beforeEach(() => {
    opened = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NATIVE_DOWNLOAD, useValue: (url: string, fileName: string) => opened.push({ url, fileName }) },
      ],
    });
    downloader = TestBed.inject(FileDownloader);
    backend = TestBed.inject(HttpTestingController);
  });

  // verify() fails if any unexpected HTTP request is left (e.g. a GET on the file).
  afterEach(() => backend.verify());

  async function flushLink(link: DownloadLink): Promise<void> {
    await Promise.resolve();
    const req = backend.expectOne('/api/v1/files/f-1/download-link');
    expect(req.request.method).toBe('POST');
    req.flush(link);
  }

  it('cere link-ul semnat și îl deschide în browser, fără să descarce fișierul prin HttpClient', async () => {
    const link = { url: '/api/v1/downloads/tok.sig', expiresAt: inMinutes(15) };
    const done = downloader.download(FILE, () => NOW);
    await flushLink(link);
    await expect(done).resolves.toEqual(link);

    expect(opened).toEqual([{ url: '/api/v1/downloads/tok.sig', fileName: FILE.originalName }]);
    // The only HTTP request was POST download-link; the browser performs GET /downloads/{token} natively.
    backend.expectNone((r) => r.url.includes('/downloads/'));
  });

  it('dacă link-ul primit e deja expirat (care ar da 401), cere unul nou', async () => {
    const done = downloader.download(FILE, () => NOW);
    await flushLink({ url: '/api/v1/downloads/vechi', expiresAt: inMinutes(-1) });
    await flushLink({ url: '/api/v1/downloads/nou', expiresAt: inMinutes(15) });
    await done;
    expect(opened.map((o) => o.url)).toEqual(['/api/v1/downloads/nou']);
  });

  it('se oprește după două link-uri expirate și nu deschide nimic', async () => {
    const done = downloader.download(FILE, () => NOW);
    await flushLink({ url: '/a', expiresAt: inMinutes(0) });
    await flushLink({ url: '/b', expiresAt: inMinutes(0) });
    await expect(done).rejects.toBeInstanceOf(LinkExpiredError);
    expect(opened).toEqual([]);
  });

  it('403 la crearea link-ului: nu deschide nimic și explică problema de rol', async () => {
    const done = downloader.download(FILE, () => NOW);
    await Promise.resolve();
    const body: ApiError = { status: 403, code: 'FORBIDDEN', message: 'Rolul DISPATCH nu are acces', traceId: 't403' };
    backend.expectOne('/api/v1/files/f-1/download-link').flush(body, { status: 403, statusText: 'Forbidden' });
    const error = await done.catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HttpErrorResponse);
    expect(describeFileError(error)).toContain('Rolul tău nu are acces');
    expect(opened).toEqual([]);
  });
});

describe('isLinkExpired', () => {
  it('consideră expirat un link cu mai puțin de 30 s rămase', () => {
    expect(isLinkExpired({ url: '/x', expiresAt: inMinutes(15) }, NOW)).toBe(false);
    expect(isLinkExpired({ url: '/x', expiresAt: new Date(NOW + 10_000).toISOString() }, NOW)).toBe(true);
    expect(isLinkExpired({ url: '/x', expiresAt: 'nu e dată' }, NOW)).toBe(true);
  });
});
