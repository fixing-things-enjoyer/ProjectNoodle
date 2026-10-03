import { ArrowDownToLine, ChevronRight, Pencil, Trash2 } from 'lucide-react';
import { downloadUrl, formatSize } from '../api';
import type { FileItem } from '../api';
import type { DialogState } from '../types';
import { ItemIcon } from './Icons';

function modifiedDate(timestamp: number | null) {
  return timestamp
    ? new Intl.DateTimeFormat(undefined, {
        month: 'short',
        day: 'numeric',
        year: 'numeric'
      }).format(timestamp)
    : '—';
}

interface Props {
  view: 'list' | 'grid';
  items: FileItem[];
  selected: Set<string>;
  setSelected: (selected: Set<string>) => void;
  toggleSelected: (path: string) => void;
  canWrite: boolean;
  navigate: (path: string) => void;
  openDialog: (dialog: DialogState) => void;
}

export default function FileViews({
  view,
  items,
  selected,
  setSelected,
  toggleSelected,
  canWrite,
  navigate,
  openDialog
}: Props) {
  const allSelected =
    items.length > 0 && items.every((item) => selected.has(item.path));
  return view === 'list' ? (
    <div className="table-scroll">
      <table className="file-table">
        <thead>
          <tr>
            <th className="check-cell">
              <input
                type="checkbox"
                aria-label="Select all visible files"
                checked={allSelected}
                onChange={() =>
                  setSelected(
                    allSelected
                      ? new Set()
                      : new Set(items.map((item) => item.path))
                  )
                }
              />
            </th>
            <th>Name</th>
            <th className="date-cell">Modified</th>
            <th className="size-cell">Size</th>
            <th className="actions-cell">
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr
              key={item.path}
              className={selected.has(item.path) ? 'selected' : ''}
            >
              <td className="check-cell">
                <input
                  type="checkbox"
                  aria-label={`Select ${item.name}`}
                  checked={selected.has(item.path)}
                  onChange={() => toggleSelected(item.path)}
                />
              </td>
              <td>
                <div className="file-name">
                  <ItemIcon item={item} />
                  {item.type === 'directory' ? (
                    <button
                      className="file-link"
                      onClick={() => navigate(item.path)}
                    >
                      {item.name}
                      <span>Folder</span>
                    </button>
                  ) : (
                    <a
                      className="file-link"
                      href={downloadUrl(item.path)}
                      download={item.name}
                    >
                      {item.name}
                      <span>
                        {item.name.includes('.')
                          ? item.name.split('.').pop()?.toUpperCase() + ' file'
                          : 'File'}
                      </span>
                    </a>
                  )}
                </div>
              </td>
              <td className="date-cell">{modifiedDate(item.lastModified)}</td>
              <td className="size-cell">{formatSize(item.size)}</td>
              <td className="actions-cell">
                <div className="row-actions">
                  {item.type === 'file' && (
                    <a
                      className="icon-button"
                      href={downloadUrl(item.path)}
                      download={item.name}
                      aria-label={`Download ${item.name}`}
                    >
                      <ArrowDownToLine size={17} />
                    </a>
                  )}
                  <button
                    className="icon-button"
                    disabled={!canWrite || item.canWrite === false}
                    aria-label={`Rename ${item.name}`}
                    onClick={() =>
                      openDialog({
                        kind: 'rename',
                        items: [item]
                      })
                    }
                  >
                    <Pencil size={16} />
                  </button>
                  <button
                    className="icon-button danger"
                    disabled={!canWrite || item.canWrite === false}
                    aria-label={`Delete ${item.name}`}
                    onClick={() =>
                      openDialog({
                        kind: 'delete',
                        items: [item]
                      })
                    }
                  >
                    <Trash2 size={16} />
                  </button>
                  {item.type === 'directory' && (
                    <button
                      className="icon-button"
                      aria-label={`Open ${item.name}`}
                      onClick={() => navigate(item.path)}
                    >
                      <ChevronRight size={18} />
                    </button>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <div className="file-grid">
      {items.map((item) => (
        <article
          key={item.path}
          className={`file-card ${selected.has(item.path) ? 'selected' : ''}`}
        >
          <div className="card-top">
            <ItemIcon item={item} />
            <input
              type="checkbox"
              aria-label={`Select ${item.name}`}
              checked={selected.has(item.path)}
              onChange={() => toggleSelected(item.path)}
            />
          </div>
          {item.type === 'directory' ? (
            <button className="card-name" onClick={() => navigate(item.path)}>
              {item.name}
            </button>
          ) : (
            <a
              className="card-name"
              href={downloadUrl(item.path)}
              download={item.name}
            >
              {item.name}
            </a>
          )}
          <div className="card-meta">
            {item.type === 'directory' ? 'Folder' : formatSize(item.size)}
            <button
              className="icon-button small"
              disabled={!canWrite || item.canWrite === false}
              aria-label={`Rename ${item.name}`}
              onClick={() => openDialog({ kind: 'rename', items: [item] })}
            >
              <Pencil size={14} />
            </button>
          </div>
        </article>
      ))}
    </div>
  );
}
