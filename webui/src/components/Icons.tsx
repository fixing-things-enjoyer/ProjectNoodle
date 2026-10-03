import {
  File,
  FileArchive,
  FileText,
  Folder,
  Image,
  Music2
} from 'lucide-react';
import { fileCategory } from '../api';
import type { FileItem } from '../api';

export function NoodleMark() {
  return (
    <svg viewBox="0 0 40 40" fill="none" aria-hidden="true">
      <path
        d="M10 29V14a6 6 0 0 1 12 0v12a4 4 0 0 0 8 0V11"
        stroke="currentColor"
        strokeWidth="4"
        strokeLinecap="round"
      />
    </svg>
  );
}
export function ItemIcon({ item }: { item: FileItem }) {
  const category = fileCategory(item);
  const Icon =
    item.type === 'directory'
      ? Folder
      : category === 'images'
        ? Image
        : category === 'documents'
          ? FileText
          : category === 'media'
            ? Music2
            : /\.(zip|gz|tar|7z|rar)$/i.test(item.name)
              ? FileArchive
              : File;
  return (
    <span
      className={`file-icon ${item.type === 'directory' ? 'folder' : category}`}
    >
      <Icon size={21} strokeWidth={1.7} />
    </span>
  );
}
