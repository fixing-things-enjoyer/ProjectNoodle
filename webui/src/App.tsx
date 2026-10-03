import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowDownToLine,
  ArrowLeft,
  ArrowUpRight,
  Check,
  CheckCheck,
  ChevronRight,
  CircleHelp,
  File,
  Folder,
  FolderOpen,
  FolderPlus,
  LayoutGrid,
  List,
  LoaderCircle,
  Moon,
  RefreshCw,
  Search,
  ShieldCheck,
  Smartphone,
  Sun,
  Trash2,
  Upload,
  Wifi,
  X,
  Pencil,
  AlertCircle
} from 'lucide-react';
import {
  ApiError,
  fileCategory,
  formatSize,
  listFiles,
  mutate,
  uploadFile
} from './api';
import type { Category, Listing } from './api';
import type { Transfer, DialogState } from './types';
import { NoodleMark } from './components/Icons';
import FileViews from './components/FileViews';
import FileDialog from './components/FileDialog';

const categories: { value: Category; label: string }[] = [
  { value: 'all', label: 'All files' },
  { value: 'images', label: 'Images' },
  { value: 'documents', label: 'Documents' },
  { value: 'media', label: 'Media' }
];

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : 'Something went wrong. Try again.';
}
function validName(name: string) {
  return (
    name.trim().length > 0 &&
    name !== '.' &&
    name !== '..' &&
    !/[\/\\\x00-\x1f\x7f]/.test(name)
  );
}
export default function App() {
  const [path, setPath] = useState(
    () => new URLSearchParams(location.hash.slice(1)).get('path') || '/'
  );
  const [listing, setListing] = useState<Listing | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<Error | null>(null);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<Category>('all');
  const [sort, setSort] = useState('name');
  const [view, setView] = useState<'list' | 'grid'>(() => {
    try {
      return localStorage.getItem('noodle-view') === 'grid' ? 'grid' : 'list';
    } catch {
      return 'list';
    }
  });
  const [section, setSection] = useState<'files' | 'transfers'>('files');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dialog, setDialog] = useState<DialogState | null>(null);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState('');
  const [toast, setToast] = useState('');
  const [dragging, setDragging] = useState(false);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [theme, setTheme] = useState(
    document.documentElement.dataset.theme || 'light'
  );
  const [revision, setRevision] = useState(0);
  const fileInput = useRef<HTMLInputElement>(null);
  const modal = useRef<HTMLDialogElement>(null);
  const dragDepth = useRef(0);
  const transferId = useRef(0);
  const controllers = useRef(new Map<number, AbortController>());
  const listingRequest = useRef(0);

  const refresh = useCallback(() => setRevision((value) => value + 1), []);
  const navigate = useCallback((nextPath: string) => {
    const hash = `path=${encodeURIComponent(nextPath)}`;
    if (location.hash.slice(1) === hash) {
      setSection('files');
      return;
    }
    location.hash = hash;
    setSection('files');
  }, []);
  useEffect(() => {
    const onHashChange = () => {
      setPath(new URLSearchParams(location.hash.slice(1)).get('path') || '/');
      setSelected(new Set());
      setQuery('');
      setCategory('all');
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const requestId = ++listingRequest.current;
    setLoading(true);
    setLoadError(null);
    setListing(null);
    setSelected(new Set());
    listFiles(path, controller.signal)
      .then((data) => {
        if (requestId !== listingRequest.current) return;
        setListing({
          ...data,
          items: data.items.filter((item) => item.name !== '..')
        });
        setLoading(false);
      })
      .catch((error) => {
        if (controller.signal.aborted || requestId !== listingRequest.current)
          return;
        setLoadError(
          error instanceof Error ? error : new Error(errorMessage(error))
        );
        setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision]);
  // Check the live session without clearing filters or interrupting a dialog/upload.
  useEffect(() => {
    if (!listing || loading || dialog) return;
    const controller = new AbortController();
    const timer = setInterval(() => {
      if (document.hidden || controllers.current.size) return;
      listFiles(path, controller.signal)
        .then((data) => {
          if (controller.signal.aborted) return;
          setListing({
            ...data,
            items: data.items.filter((item) => item.name !== '..')
          });
          setSelected(
            (current) =>
              new Set(
                [...current].filter((itemPath) =>
                  data.items.some((item) => item.path === itemPath)
                )
              )
          );
        })
        .catch((error) => {
          if (controller.signal.aborted) return;
          setListing(null);
          setLoadError(
            error instanceof Error ? error : new Error(errorMessage(error))
          );
        });
    }, 20_000);
    return () => {
      clearInterval(timer);
      controller.abort();
    };
  }, [listing, loading, dialog, path]);
  useEffect(() => {
    if (!(loadError instanceof ApiError) || loadError.status !== 401) return;
    const timer = setTimeout(refresh, 3000);
    return () => clearTimeout(timer);
  }, [loadError, refresh]);
  useEffect(() => {
    if (dialog) modal.current?.showModal();
    else modal.current?.close();
  }, [dialog]);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 5000);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    document.title = `Noodle · ${listing?.sharedFolderName || 'Shared files'}`;
  }, [listing]);
  useEffect(() => {
    const activeControllers = controllers.current;
    return () => activeControllers.forEach((controller) => controller.abort());
  }, []);

  const items = useMemo(
    () =>
      (listing?.items || [])
        .filter(
          (item) =>
            item.name.toLocaleLowerCase().includes(query.toLocaleLowerCase()) &&
            (category === 'all' ||
              (item.type === 'file' && fileCategory(item) === category))
        )
        .sort((a, b) => {
          if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
          return sort === 'modified'
            ? (b.lastModified || 0) - (a.lastModified || 0)
            : sort === 'size'
              ? (b.size || 0) - (a.size || 0)
              : a.name.localeCompare(b.name, undefined, {
                  numeric: true,
                  sensitivity: 'base'
                });
        }),
    [listing, query, category, sort]
  );
  const activeTransfers = transfers.filter(
    (transfer) => transfer.state === 'uploading' || transfer.state === 'queued'
  );
  const canWrite = Boolean(listing?.canWrite) && !loading && !loadError;
  const rootName = listing?.sharedFolderName || 'Shared files';
  const crumbs = path.split('/').filter(Boolean);
  const currentName = crumbs.at(-1) || rootName;
  const selectedItems =
    listing?.items.filter((item) => selected.has(item.path)) || [];

  function toggleSelected(itemPath: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(itemPath)) next.delete(itemPath);
      else next.add(itemPath);
      return next;
    });
  }
  function openDialog(next: DialogState) {
    setName(next.kind === 'rename' ? next.items?.[0].name || '' : '');
    setDialogError('');
    setDialog(next);
  }
  function changeTheme() {
    const next = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem('noodle-theme', next);
    } catch {
      /* Storage unavailable. */
    }
  }
  function changeView(next: 'list' | 'grid') {
    setView(next);
    try {
      localStorage.setItem('noodle-view', next);
    } catch {
      /* Storage unavailable. */
    }
  }

  async function submitDialog(event: React.FormEvent) {
    event.preventDefault();
    if (!dialog || busy) return;
    if (dialog.kind !== 'delete' && !validName(name)) {
      setDialogError(
        'Use a name without slashes. “.” and “..” are not allowed.'
      );
      return;
    }
    setBusy(true);
    setDialogError('');
    try {
      if (dialog.kind === 'folder') {
        await mutate('mkdir', { path, newDirName: name });
        setToast(`Created “${name}”.`);
      }
      if (dialog.kind === 'rename' && dialog.items?.[0]) {
        await mutate('rename', { path: dialog.items[0].path, newName: name });
        setToast(`Renamed to “${name}”.`);
      }
      if (dialog.kind === 'delete') {
        let removed = 0;
        for (const item of dialog.items || []) {
          try {
            await mutate('delete', { path: item.path });
            removed++;
          } catch (error) {
            if (removed) {
              refresh();
              setDialog({ ...dialog, items: dialog.items?.slice(removed) });
            }
            throw new Error(
              `${removed ? `${removed} deleted. ` : ''}${errorMessage(error)}`
            );
          }
        }
        setToast(`${removed} ${removed === 1 ? 'item' : 'items'} deleted.`);
      }
      setDialog(null);
      refresh();
    } catch (error) {
      setDialogError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  // One upload at a time keeps memory use low on the phone; concurrent batches are queued too.
  const uploadChain = useRef(Promise.resolve());
  function queueFiles(files: File[]) {
    if (!canWrite || !files.length) return;
    const destination = path;
    const batch: Transfer[] = files.map((file) => ({
      id: ++transferId.current,
      file,
      path: destination,
      progress: 0,
      state: 'queued'
    }));
    batch.forEach((transfer) =>
      controllers.current.set(transfer.id, new AbortController())
    );
    setTransfers((current) => [...current, ...batch]);
    const update = (id: number, change: Partial<Transfer>) =>
      setTransfers((current) =>
        current.map((transfer) =>
          transfer.id === id ? { ...transfer, ...change } : transfer
        )
      );
    uploadChain.current = uploadChain.current.then(async () => {
      let completed = 0;
      for (const transfer of batch) {
        const controller = controllers.current.get(transfer.id)!;
        if (controller.signal.aborted) {
          update(transfer.id, { state: 'canceled' });
          controllers.current.delete(transfer.id);
          continue;
        }
        update(transfer.id, { state: 'uploading' });
        try {
          await uploadFile(
            transfer.file,
            destination,
            (progress) => update(transfer.id, { progress }),
            controller.signal
          );
          update(transfer.id, { state: 'done', progress: 100 });
          completed++;
        } catch (error) {
          update(transfer.id, {
            state: controller.signal.aborted ? 'canceled' : 'error',
            error: errorMessage(error)
          });
        } finally {
          controllers.current.delete(transfer.id);
        }
      }
      if (completed) {
        setToast(
          `${completed} ${completed === 1 ? 'file' : 'files'} uploaded.`
        );
        refresh();
      }
    });
  }

  return (
    <div
      className="app-shell"
      onDragEnter={(event) => {
        if (canWrite && event.dataTransfer.types.includes('Files')) {
          event.preventDefault();
          dragDepth.current++;
          setDragging(true);
        }
      }}
      onDragOver={(event) => {
        if (event.dataTransfer.types.includes('Files')) event.preventDefault();
      }}
      onDragLeave={(event) => {
        if (
          event.dataTransfer.types.includes('Files') &&
          --dragDepth.current <= 0
        ) {
          dragDepth.current = 0;
          setDragging(false);
        }
      }}
      onDrop={(event) => {
        event.preventDefault();
        dragDepth.current = 0;
        setDragging(false);
        queueFiles(Array.from(event.dataTransfer.files));
      }}
    >
      <a
        href="#main-content"
        className="skip-link"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById('main-content')?.focus();
        }}
      >
        Skip to files
      </a>
      <aside className="sidebar">
        <a
          className="brand"
          href="#path=%2F"
          onClick={() => setSection('files')}
          aria-label="Noodle home"
        >
          <span className="brand-mark">
            <NoodleMark />
          </span>
          <span>
            Noodle<span className="brand-caption">A little closer.</span>
          </span>
        </a>
        <div className="sidebar-label">WORKSPACE</div>
        <nav aria-label="Workspace">
          <button
            className={`nav-item ${section === 'files' ? 'active' : ''}`}
            onClick={() => setSection('files')}
          >
            <FolderOpen size={19} /> Shared files{' '}
            <ChevronRight className="nav-arrow" size={16} />
          </button>
          <button
            className={`nav-item ${section === 'transfers' ? 'active' : ''}`}
            onClick={() => setSection('transfers')}
          >
            <ArrowDownToLine size={19} /> Transfers{' '}
            {activeTransfers.length > 0 && (
              <span className="nav-count">{activeTransfers.length}</span>
            )}
          </button>
        </nav>
        <div className="device-card">
          <span className="device-icon">
            <Smartphone size={22} />
          </span>
          <div>
            <strong>Android device</strong>
            <span>
              {listing
                ? 'Connected on your Wi-Fi'
                : loadError
                  ? 'Connection unavailable'
                  : 'Connecting…'}
            </span>
          </div>
          <span className={`status-dot ${listing ? '' : 'offline'}`} />
        </div>
        <div className="sidebar-bottom">
          <div className="local-note">
            <ShieldCheck size={18} />
            <div>
              <strong>Just your devices.</strong>
              <p>Files stay on your phone. No cloud, no account.</p>
            </div>
          </div>
          <button
            className="help-button"
            onClick={() => openDialog({ kind: 'help' })}
          >
            <CircleHelp size={18} /> How Noodle works <ArrowUpRight size={15} />
          </button>
          <span className="sidebar-footnote">PROJECT NOODLE</span>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="mobile-brand">
            <span className="brand-mark">
              <NoodleMark />
            </span>
            <strong>Noodle</strong>
          </div>
          <nav className="breadcrumb" aria-label="Folder path">
            <button onClick={() => navigate('/')}>
              <Folder size={16} /> {rootName}
            </button>
            {crumbs.map((crumb, index) => (
              <span key={index}>
                <ChevronRight size={14} />
                <button
                  onClick={() =>
                    navigate('/' + crumbs.slice(0, index + 1).join('/'))
                  }
                  aria-current={
                    index === crumbs.length - 1 ? 'page' : undefined
                  }
                >
                  {crumb}
                </button>
              </span>
            ))}
          </nav>
          <div className="topbar-actions">
            <span
              className={`connection-badge ${listing ? '' : 'disconnected'}`}
            >
              <span className={`status-dot ${listing ? '' : 'offline'}`} />{' '}
              {listing ? 'Connected' : loadError ? 'Offline' : 'Connecting'}
            </span>
            <button
              className="icon-button"
              aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
              onClick={changeTheme}
            >
              {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
            </button>
          </div>
        </header>
        <main id="main-content" tabIndex={-1}>
          <div className="page-heading">
            <div>
              <div className="eyebrow">YOUR PHONE. YOUR FILES.</div>
              <h1>
                {section === 'files' ? currentName : 'Transfers'}
                <span className="heading-dot">.</span>
              </h1>
              <p>
                {section === 'files'
                  ? 'A simple space to move things between your devices.'
                  : 'Follow your uploads, from here to your phone.'}
              </p>
            </div>
            <div className="heading-actions">
              {section === 'files' && (
                <button
                  className="button secondary"
                  disabled={!canWrite}
                  onClick={() => openDialog({ kind: 'folder' })}
                >
                  <FolderPlus size={17} /> New folder
                </button>
              )}
              <button
                className="button primary"
                disabled={!canWrite}
                onClick={() => fileInput.current?.click()}
              >
                <Upload size={17} /> Upload files
              </button>
            </div>
          </div>
          <input
            ref={fileInput}
            type="file"
            multiple
            className="visually-hidden"
            aria-label="Choose files to upload"
            onChange={(event) => {
              queueFiles(Array.from(event.target.files || []));
              event.target.value = '';
            }}
          />
          {section === 'files' ? (
            <>
              <div className="folder-summary">
                <span className="summary-icon">
                  <FolderOpen size={24} strokeWidth={1.6} />
                </span>
                <div>
                  <strong>{rootName}</strong>
                  <span>Shared from your Android device</span>
                </div>
                <span className="folder-access">
                  <ShieldCheck size={14} />
                  {listing?.canWrite
                    ? 'Read & write'
                    : listing
                      ? 'Read only'
                      : 'Local sharing'}
                </span>
              </div>
              <div className="files-panel">
                <div className="file-toolbar">
                  <label className="search-box">
                    <Search size={18} />
                    <input
                      placeholder="Search this folder"
                      aria-label="Search this folder"
                      value={query}
                      onChange={(event) => {
                        setQuery(event.target.value);
                        setSelected(new Set());
                      }}
                    />
                    {query && (
                      <button
                        className="icon-button small"
                        aria-label="Clear search"
                        onClick={() => setQuery('')}
                      >
                        <X size={15} />
                      </button>
                    )}
                  </label>
                  <div className="toolbar-right">
                    <label className="sort-control">
                      <span>Sort by</span>
                      <select
                        aria-label="Sort files"
                        value={sort}
                        onChange={(event) => setSort(event.target.value)}
                      >
                        <option value="name">Name</option>
                        <option value="modified">Newest</option>
                        <option value="size">Size</option>
                      </select>
                      <ArrowDown size={13} />
                    </label>
                    <div className="view-switch" aria-label="File view">
                      <button
                        aria-label="List view"
                        aria-pressed={view === 'list'}
                        className={view === 'list' ? 'active' : ''}
                        onClick={() => changeView('list')}
                      >
                        <List size={17} />
                      </button>
                      <button
                        aria-label="Grid view"
                        aria-pressed={view === 'grid'}
                        className={view === 'grid' ? 'active' : ''}
                        onClick={() => changeView('grid')}
                      >
                        <LayoutGrid size={16} />
                      </button>
                    </div>
                    <button
                      className="icon-button"
                      aria-label="Refresh folder"
                      disabled={loading}
                      onClick={refresh}
                    >
                      <RefreshCw size={17} className={loading ? 'spin' : ''} />
                    </button>
                  </div>
                </div>
                <div className="filter-row">
                  <div
                    className="filter-tabs"
                    role="group"
                    aria-label="Filter file types"
                  >
                    {categories.map((filter) => (
                      <button
                        key={filter.value}
                        className={category === filter.value ? 'active' : ''}
                        aria-pressed={category === filter.value}
                        onClick={() => {
                          setCategory(filter.value);
                          setSelected(new Set());
                        }}
                      >
                        {filter.label}
                        {filter.value === 'all' && listing && (
                          <span>{listing.items.length}</span>
                        )}
                      </button>
                    ))}
                  </div>
                  <span className="item-count">
                    {items.length} {items.length === 1 ? 'item' : 'items'}
                  </span>
                </div>
                {selected.size > 0 && (
                  <div className="selection-bar">
                    <span>
                      <CheckCheck size={16} /> {selected.size} selected
                    </span>
                    <div>
                      {selectedItems.length === 1 &&
                        selectedItems[0].canWrite !== false && (
                          <button
                            className="text-button"
                            disabled={!canWrite}
                            onClick={() =>
                              openDialog({
                                kind: 'rename',
                                items: selectedItems
                              })
                            }
                          >
                            <Pencil size={15} />
                            Rename
                          </button>
                        )}
                      <button
                        className="text-button danger"
                        disabled={
                          !canWrite ||
                          selectedItems.some((item) => item.canWrite === false)
                        }
                        onClick={() =>
                          openDialog({ kind: 'delete', items: selectedItems })
                        }
                      >
                        <Trash2 size={15} />
                        Delete
                      </button>
                      <button
                        className="icon-button small"
                        aria-label="Clear selection"
                        onClick={() => setSelected(new Set())}
                      >
                        <X size={16} />
                      </button>
                    </div>
                  </div>
                )}
                {loading ? (
                  <div className="empty-state" role="status">
                    <LoaderCircle className="spin" size={28} />
                    <h2>Opening your folder…</h2>
                    <p>Getting the latest files from your phone.</p>
                  </div>
                ) : loadError ? (
                  <div className="empty-state" role="alert">
                    <span className="empty-icon">
                      {loadError instanceof ApiError &&
                      loadError.status === 401 ? (
                        <Smartphone size={30} />
                      ) : (
                        <Wifi size={30} />
                      )}
                    </span>
                    <h2>
                      {loadError instanceof ApiError && loadError.status === 401
                        ? 'One quick approval'
                        : 'Couldn’t open this folder'}
                    </h2>
                    <p>
                      {loadError instanceof ApiError && loadError.status === 401
                        ? 'Open Noodle on your phone and approve this device. We’ll connect automatically.'
                        : errorMessage(loadError)}
                    </p>
                    <div className="empty-actions">
                      <button className="button secondary" onClick={refresh}>
                        <RefreshCw size={16} /> Try again
                      </button>
                      {path !== '/' && (
                        <button
                          className="button secondary"
                          onClick={() => navigate('/')}
                        >
                          Shared folder
                        </button>
                      )}
                    </div>
                  </div>
                ) : !items.length ? (
                  <div className="empty-state">
                    <span className="empty-icon">
                      <FolderOpen size={32} strokeWidth={1.4} />
                    </span>
                    <h2>
                      {query || category !== 'all'
                        ? 'No matching files'
                        : 'Room for something new'}
                    </h2>
                    <p>
                      {query || category !== 'all'
                        ? 'Try another search or file type.'
                        : 'Drop files here, or choose Upload files to send them to your phone.'}
                    </p>
                    {query || category !== 'all' ? (
                      <button
                        className="button secondary"
                        onClick={() => {
                          setQuery('');
                          setCategory('all');
                        }}
                      >
                        Clear filters
                      </button>
                    ) : (
                      <button
                        className="button primary"
                        disabled={!canWrite}
                        onClick={() => fileInput.current?.click()}
                      >
                        <Upload size={17} /> Upload files
                      </button>
                    )}
                  </div>
                ) : (
                  <FileViews
                    view={view}
                    items={items}
                    selected={selected}
                    setSelected={setSelected}
                    toggleSelected={toggleSelected}
                    canWrite={canWrite}
                    navigate={navigate}
                    openDialog={openDialog}
                  />
                )}
                <div className="panel-footer">
                  <span>
                    {path !== '/' ? (
                      <button
                        className="text-button"
                        onClick={() =>
                          navigate('/' + crumbs.slice(0, -1).join('/'))
                        }
                      >
                        <ArrowLeft size={14} /> Parent folder
                      </button>
                    ) : (
                      <>
                        <ShieldCheck size={14} /> Files stay on your device
                      </>
                    )}
                  </span>
                  <span>
                    {canWrite
                      ? 'Drag & drop to upload'
                      : listing
                        ? 'This folder is read only'
                        : 'Same Wi-Fi. Simple sharing.'}
                  </span>
                </div>
              </div>
              {activeTransfers.length > 0 && (
                <button
                  className="upload-progress-banner"
                  onClick={() => setSection('transfers')}
                >
                  <LoaderCircle className="spin" size={18} />
                  <span>
                    Sending {activeTransfers.length}{' '}
                    {activeTransfers.length === 1 ? 'file' : 'files'} to your
                    phone
                  </span>
                  <span>
                    View transfers <ChevronRight size={16} />
                  </span>
                </button>
              )}
            </>
          ) : (
            <div className="transfers-panel">
              {transfers.length ? (
                <>
                  <div className="transfers-header">
                    <h2>
                      Uploads <span>{transfers.length}</span>
                    </h2>
                    <button
                      className="text-button"
                      disabled={activeTransfers.length === transfers.length}
                      onClick={() =>
                        setTransfers((current) =>
                          current.filter(
                            (transfer) =>
                              transfer.state === 'uploading' ||
                              transfer.state === 'queued'
                          )
                        )
                      }
                    >
                      Clear completed
                    </button>
                  </div>
                  {transfers.map((transfer) => (
                    <div className="transfer-row" key={transfer.id}>
                      <span
                        className={`file-icon ${transfer.state === 'done' ? 'success' : 'documents'}`}
                      >
                        {transfer.state === 'done' ? (
                          <Check size={21} />
                        ) : transfer.state === 'error' ? (
                          <AlertCircle size={21} />
                        ) : (
                          <File size={21} />
                        )}
                      </span>
                      <div className="transfer-detail">
                        <strong>{transfer.file.name}</strong>
                        <span>
                          {formatSize(transfer.file.size)} ·{' '}
                          {transfer.state === 'done'
                            ? 'On your phone'
                            : transfer.state === 'uploading'
                              ? transfer.progress === 100
                                ? 'Saving on your phone…'
                                : `${transfer.progress}% uploaded`
                              : transfer.state === 'queued'
                                ? 'Waiting to upload'
                                : transfer.state === 'canceled'
                                  ? 'Canceled'
                                  : transfer.error}
                        </span>
                        {transfer.state === 'uploading' && (
                          <progress
                            value={transfer.progress}
                            max={100}
                            aria-label={`Upload progress for ${transfer.file.name}`}
                          />
                        )}
                      </div>
                      {['uploading', 'queued'].includes(transfer.state) && (
                        <button
                          className="icon-button"
                          aria-label={`Cancel upload of ${transfer.file.name}`}
                          onClick={() => {
                            controllers.current.get(transfer.id)?.abort();
                            setTransfers((current) =>
                              current.map((entry) =>
                                entry.id === transfer.id
                                  ? { ...entry, state: 'canceled' }
                                  : entry
                              )
                            );
                          }}
                        >
                          <X size={17} />
                        </button>
                      )}
                      {transfer.state === 'error' && (
                        <button
                          className="text-button"
                          disabled={!canWrite}
                          onClick={() => {
                            setTransfers((current) =>
                              current.filter(
                                (entry) => entry.id !== transfer.id
                              )
                            );
                            queueFiles([transfer.file]);
                          }}
                        >
                          Retry in this folder
                        </button>
                      )}
                    </div>
                  ))}
                </>
              ) : (
                <div className="empty-state">
                  <span className="empty-icon">
                    <ArrowDownToLine size={30} />
                  </span>
                  <h2>Nothing in transit</h2>
                  <p>
                    Your uploads will appear here. Choose a few files to get
                    started.
                  </p>
                  <button
                    className="button primary"
                    disabled={!canWrite}
                    onClick={() => fileInput.current?.click()}
                  >
                    <Upload size={17} /> Upload files
                  </button>
                </div>
              )}
            </div>
          )}
          <footer className="workspace-footer">
            <span>Less between you and your files.</span>
            <button onClick={() => openDialog({ kind: 'help' })}>
              <CircleHelp size={14} /> Need a hand?
            </button>
          </footer>
        </main>
        <nav className="mobile-nav" aria-label="Workspace">
          <button
            className={section === 'files' ? 'active' : ''}
            onClick={() => setSection('files')}
          >
            <FolderOpen size={18} />
            Files
          </button>
          <button
            className={section === 'transfers' ? 'active' : ''}
            onClick={() => setSection('transfers')}
          >
            <ArrowDownToLine size={18} />
            Transfers {activeTransfers.length || ''}
          </button>
          <button onClick={() => openDialog({ kind: 'help' })}>
            <CircleHelp size={18} />
            Help
          </button>
        </nav>
      </div>
      {dragging && (
        <div className="drop-overlay">
          <div>
            <Upload size={44} />
            <h2>Drop it like it’s local.</h2>
            <p>Upload to {currentName}</p>
          </div>
        </div>
      )}
      {toast && (
        <div className="toast" role="status">
          <span>
            <Check size={18} />
            {toast}
          </span>
          <button aria-label="Dismiss message" onClick={() => setToast('')}>
            <X size={16} />
          </button>
        </div>
      )}
      <FileDialog
        modal={modal}
        dialog={dialog}
        busy={busy}
        name={name}
        setName={setName}
        setDialog={setDialog}
        dialogError={dialogError}
        currentName={currentName}
        submitDialog={submitDialog}
      />
    </div>
  );
}
