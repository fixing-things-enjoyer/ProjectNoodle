import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import type { FileItem } from '../src/api';

const folder = (name: string): FileItem => ({
  name,
  path: '/' + name,
  type: 'directory',
  size: null,
  lastModified: 1760054400000,
  canWrite: true
});
const file = (name: string, size = 1024, path = '/' + name): FileItem => ({
  name,
  path,
  type: 'file',
  size,
  lastModified: 1760054400000,
  canWrite: true
});

test.beforeEach(async ({ page }, testInfo) => {
  if (testInfo.title.includes('background connection'))
    await page.clock.install();
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  let items = [
    folder('Camera'),
    folder('Documents'),
    file('Autumn in Kyoto.jpg', 4_381_024),
    file('Project notes.pdf', 283_624),
    file('Road trip playlist.mp3', 7_224_524),
    file('weekend plans.txt', 384),
    file('Archive.zip', 21_102_528),
    file('budget + 10%.csv', 2058)
  ];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const values = new URLSearchParams(request.postData() || '');
    let status = 200;
    let body: object = { status: 'success' };
    if (url.pathname === '/api/list') {
      body = {
        currentPath: url.searchParams.get('path'),
        sharedFolderName: 'Downloads',
        canWrite: true,
        items:
          url.searchParams.get('path') === '/'
            ? items
            : [file('Sunset.jpg', 2048, '/Camera/Sunset.jpg')]
      };
    } else if (url.pathname === '/api/mkdir') {
      const name = values.get('newDirName')!;
      if (items.some((item) => item.name === name)) {
        status = 409;
        body = { message: 'An item with this name already exists.' };
      } else {
        items = [...items, folder(name)];
        status = 201;
      }
    } else if (url.pathname === '/api/rename') {
      items = items.map((item) =>
        item.path === values.get('path')
          ? {
              ...item,
              name: values.get('newName')!,
              path: '/' + values.get('newName')
            }
          : item
      );
    } else if (url.pathname === '/api/delete')
      items = items.filter((item) => item.path !== values.get('path'));
    else if (url.pathname === '/api/upload') {
      items = [...items, file('hello.txt', 5)];
      status = 201;
    }
    await route.fulfill({ status, json: body });
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Downloads.' })).toBeVisible();
  await expect(
    page.getByRole('link', { name: /^weekend plans.txt/ })
  ).toBeVisible();
});

test('browse folders and browser history; encode literal filenames', async ({
  page
}) => {
  await expect(
    page.getByRole('link', { name: /budget \+ 10%.csv/ }).first()
  ).toHaveAttribute('href', '/files/budget%20%2B%2010%25.csv?download=1');
  await page
    .getByRole('button', { name: 'Camera Folder', exact: true })
    .click();
  await expect(page.getByRole('heading', { name: 'Camera.' })).toBeVisible();
  await expect(page.getByRole('link', { name: /^Sunset.jpg/ })).toBeVisible();
  await page.goBack();
  await expect(
    page.getByRole('link', { name: /^weekend plans.txt/ })
  ).toBeVisible();
});

test('search, filter and switch grid view', async ({ page }) => {
  await page
    .getByRole('textbox', { name: 'Search this folder' })
    .fill('weekend');
  await expect(
    page.getByRole('link', { name: /^weekend plans.txt/ })
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: /^Project notes.pdf/ })
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Clear search' }).click();
  await page.getByRole('button', { name: 'Images', exact: true }).click();
  await expect(
    page.getByRole('link', { name: /^Autumn in Kyoto.jpg/ })
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: /^weekend plans.txt/ })
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Grid view' }).click();
  await expect(page.locator('.file-card')).toHaveCount(1);
});

test('create, rename and delete with confirmation', async ({ page }) => {
  await page.getByRole('button', { name: 'New folder', exact: true }).click();
  await page.getByRole('textbox', { name: 'Folder name' }).fill('Travel + 20%');
  await page
    .getByRole('button', { name: 'Create folder', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Travel + 20% Folder', exact: true })
  ).toBeVisible();
  await page
    .getByRole('checkbox', { name: 'Select Travel + 20%', exact: true })
    .check();
  await page.getByRole('button', { name: 'Rename', exact: true }).click();
  await page.getByRole('textbox', { name: 'New name' }).fill('New adventures');
  await page.getByRole('button', { name: 'Save name', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'New adventures Folder', exact: true })
  ).toBeVisible();
  await page
    .getByRole('checkbox', { name: 'Select New adventures', exact: true })
    .check();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('This cannot be undone');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'New adventures Folder', exact: true })
  ).toBeVisible();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Delete', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'New adventures Folder', exact: true })
  ).toHaveCount(0);
});

test('duplicate names show recoverable errors; Escape returns focus', async ({
  page
}) => {
  await page.getByRole('button', { name: 'New folder', exact: true }).click();
  await page.getByRole('textbox', { name: 'Folder name' }).fill('Camera');
  await page
    .getByRole('button', { name: 'Create folder', exact: true })
    .click();
  await expect(page.getByRole('alert')).toContainText('already exists');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(
    page.getByRole('button', { name: 'New folder', exact: true })
  ).toBeFocused();
});

test('uploads finish and appear in transfers', async ({ page }, testInfo) => {
  await page.getByLabel('Choose files to upload').setInputFiles({
    name: 'hello.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('hello')
  });
  await expect(
    page.getByRole('status').filter({ hasText: 'file uploaded' })
  ).toContainText('1 file uploaded');
  if (testInfo.project.name === 'mobile')
    await page
      .getByRole('button', { name: /^Transfers/ })
      .last()
      .click();
  else
    await page
      .getByRole('button', { name: /^Transfers/ })
      .first()
      .click();
  await expect(page.getByRole('heading', { name: 'Transfers.' })).toBeVisible();
  await expect(page.locator('.transfer-row')).toContainText('hello.txt');
  await expect(page.locator('.transfer-row')).toContainText('On your phone');
});

test('approval automatically retries and connects', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/list?*', (route) =>
    route.fulfill({
      status: ++attempts === 1 ? 401 : 200,
      json:
        attempts === 1
          ? { message: 'Approval required' }
          : {
              sharedFolderName: 'Downloads',
              currentPath: '/',
              canWrite: true,
              items: []
            }
    })
  );
  await page.getByRole('button', { name: 'Refresh folder' }).click();
  await expect(
    page.getByRole('heading', { name: 'One quick approval' })
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Room for something new' })
  ).toBeVisible({ timeout: 8000 });
});

test('read-only folders disable mutation controls', async ({ page }) => {
  await page.route('**/api/list?*', (route) =>
    route.fulfill({
      json: {
        sharedFolderName: 'Downloads',
        currentPath: '/',
        canWrite: false,
        items: [file('notes.txt')]
      }
    })
  );
  await page.getByRole('button', { name: 'Refresh folder' }).click();
  await expect(page.getByText('This folder is read only')).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'New folder', exact: true })
  ).toBeDisabled();
  await expect(
    page.getByRole('button', { name: 'Upload files', exact: true }).first()
  ).toBeDisabled();
  await expect(
    page.getByRole('link', { name: /^notes.txt/ }).first()
  ).toBeVisible();
});

test('network error has retry and never shows stale files', async ({
  page
}) => {
  await page.route('**/api/list?*', (route) => route.abort('failed'));
  await page.getByRole('button', { name: 'Refresh folder' }).click();
  await expect(
    page.getByRole('heading', { name: 'Couldn’t open this folder' })
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: /^weekend plans.txt/ })
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Upload files', exact: true }).first()
  ).toBeDisabled();
});

test('light and dark layouts pass accessibility checks', async ({
  page
}, testInfo) => {
  await page.evaluate(() => {
    document.documentElement.dataset.theme = 'light';
  });
  await expect(page.locator('body')).toHaveJSProperty(
    'scrollWidth',
    await page.locator('body').evaluate((el) => el.clientWidth)
  );
  const light = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(light.violations).toEqual([]);
  await page.screenshot({
    path: `test-results/${testInfo.project.name}-light.png`,
    fullPage: true
  });
  await page.getByRole('button', { name: /Switch to .* theme/ }).click();
  const dark = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(dark.violations).toEqual([]);
  await page.screenshot({
    path: `test-results/${testInfo.project.name}-dark.png`,
    fullPage: true
  });
  await page.getByRole('button', { name: 'New folder', exact: true }).click();
  const modal = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze();
  expect(modal.violations).toEqual([]);
});

test('partial bulk deletion can retry only the remaining item', async ({
  page
}) => {
  let fail = true;
  await page.route('**/api/delete', async (route) => {
    const path = new URLSearchParams(route.request().postData() || '').get(
      'path'
    );
    if (fail && path === '/Archive.zip') {
      fail = false;
      await route.fulfill({
        status: 500,
        json: { message: 'Temporarily unavailable. Try again.' }
      });
    } else await route.fallback();
  });
  await page
    .getByRole('checkbox', { name: 'Select weekend plans.txt', exact: true })
    .check();
  await page
    .getByRole('checkbox', { name: 'Select Archive.zip', exact: true })
    .check();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Delete', exact: true })
    .click();
  await expect(page.getByRole('alert')).toContainText('1 deleted');
  await expect(
    page.getByRole('dialog').locator('.delete-items')
  ).not.toContainText('weekend plans.txt');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Delete', exact: true })
    .click();
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(page.getByRole('link', { name: /^Archive.zip/ })).toHaveCount(0);
});

test('queued uploads cancel immediately and never send', async ({
  page
}, testInfo) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let sent = 0;
  await page.route('**/api/upload?*', async (route) => {
    sent++;
    await gate;
    await route.fulfill({ status: 201, json: { status: 'success' } });
  });
  await page.getByLabel('Choose files to upload').setInputFiles([
    { name: 'first.txt', mimeType: 'text/plain', buffer: Buffer.from('one') },
    { name: 'second.txt', mimeType: 'text/plain', buffer: Buffer.from('two') }
  ]);
  const navigation = page.getByRole('button', { name: /^Transfers/ });
  await (
    testInfo.project.name === 'mobile' ? navigation.last() : navigation.first()
  ).click();
  await page
    .getByRole('button', { name: 'Cancel upload of second.txt', exact: true })
    .click();
  await expect(
    page.locator('.transfer-row').filter({ hasText: 'second.txt' })
  ).toContainText('Canceled');
  release();
  await expect(
    page.locator('.transfer-row').filter({ hasText: 'first.txt' })
  ).toContainText('On your phone');
  expect(sent).toBe(1);
});

test('background connection check detects stopped sharing', async ({
  page
}) => {
  await page.route('**/api/list?*', (route) => route.abort('failed'));
  await page.clock.fastForward(21_000);
  await expect(
    page.getByRole('heading', { name: 'Couldn’t open this folder' })
  ).toBeVisible();
  await expect(
    page.getByText('Keep Noodle running', { exact: false })
  ).toBeVisible();
});
