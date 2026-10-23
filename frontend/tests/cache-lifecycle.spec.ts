import { test, expect } from '@playwright/test';
import { spawn, execFileSync, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import path from 'node:path';

// Dedicated containers only. The real application's MongoDB and Redis are never used.
const database = `shortener_browser_${Date.now()}`;
let backend: ChildProcess | undefined;
let backendLog = '';
const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

async function startBackend() {
  let occupied = false;
  try { occupied = (await fetch('http://localhost:8080/api/links')).ok; } catch { /* free */ }
  if (occupied) throw new Error('Port 8080 is occupied. Stop the development backend before running this isolated test.');
  backendLog = '';
  backend = spawn('java', ['-jar', path.resolve('../backend/target/acortador-hexagonal-0.0.1-SNAPSHOT.jar'),
    '--server.port=8080', `--spring.data.mongodb.uri=mongodb://127.0.0.1:27018/${database}`,
    '--spring.data.redis.host=127.0.0.1', '--spring.data.redis.port=6380',
    '--app.public-base-url=http://localhost:8080'], { windowsHide: true });
  backend.stdout?.on('data', chunk => { backendLog = (backendLog + chunk).slice(-12000); });
  backend.stderr?.on('data', chunk => { backendLog = (backendLog + chunk).slice(-12000); });
  for (let i = 0; i < 120; i++) {
    if (backend.exitCode !== null) throw new Error(`Backend stopped: ${backendLog}`);
    try { if ((await fetch('http://localhost:8080/api/links')).ok) return; } catch { /* starting */ }
    await pause(500);
  }
  throw new Error(`Backend did not start: ${backendLog}`);
}

async function stopBackend() {
  if (backend && backend.exitCode === null) {
    const exited = once(backend, 'exit');
    // Oracle's Windows java launcher can spawn a child JVM: stop our entire process tree.
    if (process.platform === 'win32') {
      execFileSync('taskkill', ['/PID', String(backend.pid), '/T', '/F'], { windowsHide: true });
    } else backend.kill();
    await exited;
  }
}

test.afterAll(stopBackend);

test('one cache lifetime survives navigation, reload, expiry, concurrent creation and full restarts', async ({ page, request }) => {
  execFileSync('docker', ['exec', 'shortener_fix_test_mongo', 'mongosh', database, '--quiet', '--eval',
    'db.sequences.insertOne({_id:"links",value:Date.now()})'], { windowsHide: true });
  await startBackend();
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  const create = async (url: string) => {
    const response = await request.post('http://localhost:8080/api/links', { data: { url } });
    expect(response.ok()).toBeTruthy();
    return response.json() as Promise<{ id: number; originalUrl: string; shortenedUrl: string }>;
  };
  const status = async (id: number) => {
    const response = await request.get('http://localhost:8080/api/links');
    return (await response.json()).cacheStatuses.find((entry: { id: number }) => entry.id === id);
  };
  const row = (id: number) => page.getByRole('row').filter({ has: page.getByRole('cell', { name: new RegExp(`^#?${id}$`) }) });
  await page.goto('/');
  await page.getByRole('tab', { name: 'Acortar enlace', exact: true }).click();
  await page.getByRole('textbox', { name: /^Enlace original/ }).fill('https://example.com/a');
  await page.getByRole('button', { name: 'Acortar', exact: true }).click();
  await expect(page.getByText('Enlace acortado correctamente')).toBeVisible();
  const aResponse = await request.get('http://localhost:8080/api/links');
  const a = (await aResponse.json()).links.find((link: { originalUrl: string }) => link.originalUrl === 'https://example.com/a');
  const firstTtl = (await status(a.id)).remainingMillis;
  expect(firstTtl).toBeGreaterThan(27000);
  expect(firstTtl).toBeLessThanOrEqual(30000);
  const createdAt = Date.now();
  await page.getByRole('tab', { name: 'Ver enlaces', exact: true }).click();
  await expect(row(a.id)).toHaveClass(/(^|\s)cache-active-row(\s|$)/);
  await expect(row(a.id)).toContainText('Redis activo');
  await pause(10000);
  const afterTen = (await status(a.id)).remainingMillis;
  expect(afterTen).toBeGreaterThan(15000);
  expect(afterTen).toBeLessThanOrEqual(20000);
  await page.getByRole('tab', { name: 'Acortar enlace', exact: true }).click();
  await page.getByRole('tab', { name: 'Ver enlaces', exact: true }).click();
  await page.reload();
  await expect(row(a.id)).toHaveClass(/(^|\s)cache-active-row(\s|$)/);
  expect((await status(a.id)).remainingMillis).toBeLessThanOrEqual(afterTen);
  const c = await create('https://example.com/c');
  await page.getByRole('tab', { name: 'Ver enlaces', exact: true }).click();
  await expect(row(c.id)).toHaveClass(/(^|\s)cache-active-row(\s|$)/);
  await expect(row(a.id)).toHaveClass(/(^|\s)cache-active-row(\s|$)/);
  expect((await status(c.id)).remainingMillis).toBeGreaterThan((await status(a.id)).remainingMillis);
  await page.screenshot({ path: 'test-results/independent-counters.png', fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await expect(row(a.id)).toHaveCSS('transition-duration', '0s');
  await page.screenshot({ path: 'test-results/mobile-counters.png', fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  // Hold an old active HTTP snapshot while WebSocket continues to deliver newer states.
  let releaseSnapshot!: () => void;
  let capturedSnapshot = false;
  const snapshotGate = new Promise<void>(resolve => { releaseSnapshot = resolve; });
  await page.route('**/api/links', async route => {
    if (route.request().method() !== 'GET') return route.continue();
    const response = await route.fetch();
    capturedSnapshot = true;
    await snapshotGate;
    await route.fulfill({ response });
  });
  await page.getByRole('tab', { name: 'Ver enlaces', exact: true }).click();
  await expect.poll(() => capturedSnapshot).toBeTruthy();
  // Socket must publish expiration without another GET or local countdown timer.
  await expect(row(a.id).getByText('Expirado', { exact: true })).toBeVisible({ timeout: 35000 });
  releaseSnapshot();
  await expect(page.getByText('Consultando enlaces...')).toHaveCount(0);
  await page.unroute('**/api/links');
  await expect(row(a.id).getByText('Expirado', { exact: true })).toBeVisible();
  expect(Date.now() - createdAt).toBeGreaterThan(25000);
  await expect(row(a.id)).not.toHaveClass(/(^|\s)cache-active-row(\s|$)/);
  expect((await status(a.id)).remainingMillis).toBe(0);
  expect((await create('https://EXAMPLE.com:443/a')).id).toBe(a.id);
  expect((await status(a.id)).expired).toBeTruthy();
  const b = await create('https://example.com/b');
  expect((await status(b.id)).remainingMillis).toBeGreaterThan(28000);
  expect((await status(a.id)).remainingMillis).toBe(0);
  const [d1, d2] = await Promise.all([create('https://example.com/concurrent'), create('https://example.com/concurrent')]);
  expect(d1.id).toBe(d2.id);
  const list = await request.get('http://localhost:8080/api/links');
  expect((await list.json()).links).toHaveLength(4);
  const redirect = await request.get(a.shortenedUrl, { maxRedirects: 0 });
  expect(redirect.status()).toBe(302);
  expect(redirect.headers().location).toBe('https://example.com/a');
  await stopBackend();
  execFileSync('docker', ['restart', 'shortener_fix_test_mongo'], { windowsHide: true });
  await startBackend();
  await page.reload();
  await expect(row(a.id).getByText('Expirado', { exact: true })).toBeVisible();
  expect((await create('https://example.com/a')).id).toBe(a.id);
  expect((await status(a.id)).remainingMillis).toBe(0);
  expect(errors).toEqual([]);
  console.log('Verified all 12 required scenarios with real MongoDB/Redis, browser reload and Spring Boot/MongoDB process restarts.');
});
