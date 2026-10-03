export interface FileItem {
  name: string;
  path: string;
  type: 'file' | 'directory';
  size: number | null;
  lastModified: number | null;
  canWrite?: boolean;
}

export interface Listing {
  currentPath: string;
  sharedFolderName: string;
  canWrite: boolean;
  items: FileItem[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string
  ) {
    super(message);
  }
}

const connectionMessage =
  'Connection failed. Start sharing in Noodle and connect both devices to the same Wi-Fi network.';

async function requestJson<T>(
  url: string,
  options: RequestInit = {}
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, options);
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new Error(connectionMessage);
  }
  let body;
  try {
    body = await response.json();
  } catch {
    throw new ApiError(
      response.status,
      'Could not read the response. Check that Noodle is running, then try again.'
    );
  }
  if (!response.ok)
    throw new ApiError(response.status, body.message || 'Request failed.');
  return body as T;
}

export function listFiles(
  path: string,
  signal?: AbortSignal
): Promise<Listing> {
  return requestJson(`/api/list?${new URLSearchParams({ path })}`, {
    signal,
    cache: 'no-store'
  });
}

export function mutate(
  action: 'mkdir' | 'rename' | 'delete',
  values: Record<string, string>
) {
  return requestJson(`/api/${action}`, {
    method: 'POST',
    body: new URLSearchParams(values)
  });
}

// Each segment is encoded once. Literal +, %, # and Unicode names stay intact.
export function downloadUrl(path: string) {
  return `/files${path.split('/').map(encodeURIComponent).join('/')}?download=1`;
}

export function uploadFile(
  file: File,
  path: string,
  onProgress: (progress: number) => void,
  signal: AbortSignal
): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    const abort = () => request.abort();
    const finish = () => signal.removeEventListener('abort', abort);
    request.open('POST', `/api/upload?${new URLSearchParams({ path })}`);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable)
        onProgress(Math.round((event.loaded / event.total) * 100));
    };
    request.onload = () => {
      finish();
      if (request.status >= 200 && request.status < 300) resolve();
      else {
        let message = 'Upload failed. Check your connection and try again.';
        try {
          message = JSON.parse(request.responseText).message || message;
        } catch {
          /* Non-JSON network error. */
        }
        reject(new ApiError(request.status, message));
      }
    };
    request.onerror = () => {
      finish();
      reject(new Error('Connection lost. Check the Android sharing session.'));
    };
    request.onabort = () => {
      finish();
      reject(new DOMException('Upload canceled.', 'AbortError'));
    };
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) {
      finish();
      reject(new DOMException('Upload canceled.', 'AbortError'));
      return;
    }
    const form = new FormData();
    form.append('fileName', file.name);
    form.append('file', file, file.name);
    request.send(form);
  });
}

export function formatSize(size: number | null) {
  if (size === null) return '—';
  if (size < 1024) return `${size} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  const exponent = Math.min(
    Math.floor(Math.log(size) / Math.log(1024)),
    units.length
  );
  return `${(size / 1024 ** exponent).toLocaleString(undefined, { maximumFractionDigits: 1 })} ${units[exponent - 1]}`;
}

export type Category = 'all' | 'images' | 'documents' | 'media';
export function fileCategory(item: FileItem): Category {
  const extension = item.name.split('.').pop()?.toLowerCase();
  if (
    ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'heic', 'avif'].includes(
      extension || ''
    )
  )
    return 'images';
  if (
    ['mp4', 'mov', 'webm', 'mkv', 'mp3', 'wav', 'ogg', 'm4a', 'flac'].includes(
      extension || ''
    )
  )
    return 'media';
  if (
    [
      'pdf',
      'txt',
      'md',
      'csv',
      'doc',
      'docx',
      'xls',
      'xlsx',
      'ppt',
      'pptx',
      'json'
    ].includes(extension || '')
  )
    return 'documents';
  return 'all';
}
