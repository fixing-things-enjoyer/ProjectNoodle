import type { FileItem } from './api';

export type Transfer = {
  id: number;
  file: File;
  path: string;
  progress: number;
  state: 'queued' | 'uploading' | 'done' | 'error' | 'canceled';
  error?: string;
};
export type DialogState = {
  kind: 'folder' | 'rename' | 'delete' | 'help';
  items?: FileItem[];
};
