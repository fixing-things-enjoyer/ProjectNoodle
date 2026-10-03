import {
  CircleHelp,
  FolderPlus,
  LoaderCircle,
  Pencil,
  Trash2,
  X
} from 'lucide-react';
import type { FormEvent, RefObject } from 'react';
import type { DialogState } from '../types';
import { ItemIcon } from './Icons';

interface Props {
  modal: RefObject<HTMLDialogElement | null>;
  dialog: DialogState | null;
  busy: boolean;
  name: string;
  setName: (name: string) => void;
  setDialog: (dialog: DialogState | null) => void;
  dialogError: string;
  currentName: string;
  submitDialog: (event: FormEvent) => void;
}

export default function FileDialog({
  modal,
  dialog,
  busy,
  name,
  setName,
  setDialog,
  dialogError,
  currentName,
  submitDialog
}: Props) {
  return (
    <dialog
      ref={modal}
      className="modal"
      aria-labelledby="dialog-title"
      onCancel={(event) => {
        if (busy) event.preventDefault();
        else setDialog(null);
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget && !busy) setDialog(null);
      }}
      onClose={() => {
        if (!busy) setDialog(null);
      }}
    >
      {dialog && (
        <form onSubmit={submitDialog}>
          <div className="modal-heading">
            <span className="empty-icon">
              {dialog.kind === 'delete' ? (
                <Trash2 size={25} />
              ) : dialog.kind === 'help' ? (
                <CircleHelp size={25} />
              ) : dialog.kind === 'folder' ? (
                <FolderPlus size={25} />
              ) : (
                <Pencil size={25} />
              )}
            </span>
            <button
              type="button"
              className="icon-button"
              aria-label="Close dialog"
              disabled={busy}
              onClick={() => setDialog(null)}
            >
              <X size={20} />
            </button>
          </div>
          <h2 id="dialog-title">
            {dialog.kind === 'folder'
              ? 'New folder'
              : dialog.kind === 'rename'
                ? 'Rename'
                : dialog.kind === 'delete'
                  ? `Delete ${dialog.items?.length === 1 ? 'this item' : `${dialog.items?.length} items`}?`
                  : 'Help'}
          </h2>
          {dialog.kind === 'help' ? (
            <>
              <ol className="help-steps">
                <li>
                  <span>1</span>
                  <div>
                    <strong>Choose a folder</strong>
                    <p>Choose a folder in Noodle and tap Start sharing.</p>
                  </div>
                </li>
                <li>
                  <span>2</span>
                  <div>
                    <strong>Open the address</strong>
                    <p>
                      Connect to the same Wi-Fi network or hotspot. Open the
                      address shown in Noodle in a browser.
                    </p>
                  </div>
                </li>
                <li>
                  <span>3</span>
                  <div>
                    <strong>Transfer files</strong>
                    <p>
                      Click a file to download. Use Upload files or drag and
                      drop to upload.
                    </p>
                  </div>
                </li>
              </ol>
              <button
                type="button"
                className="button primary full-width"
                onClick={() => setDialog(null)}
              >
                Close
              </button>
            </>
          ) : (
            <>
              <p>
                {dialog.kind === 'delete'
                  ? 'Delete the selected files and folders, including folder contents. This cannot be undone.'
                  : dialog.kind === 'rename'
                    ? `Rename “${dialog.items?.[0].name}”.`
                    : `Create a folder inside “${currentName}”.`}
              </p>
              {dialog.kind === 'delete' ? (
                <div className="delete-items">
                  {dialog.items?.map((item) => (
                    <span key={item.path}>
                      <ItemIcon item={item} />
                      {item.name}
                    </span>
                  ))}
                </div>
              ) : (
                <label className="name-field">
                  {dialog.kind === 'rename' ? 'New name' : 'Folder name'}
                  <input
                    autoFocus
                    required
                    maxLength={255}
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    onFocus={(event) => event.target.select()}
                    aria-invalid={Boolean(dialogError)}
                    aria-describedby={dialogError ? 'dialog-error' : undefined}
                  />
                </label>
              )}
              {dialogError && (
                <p className="form-error" id="dialog-error" role="alert">
                  {dialogError}
                </p>
              )}
              <div className="modal-actions">
                <button
                  className="button secondary"
                  type="button"
                  disabled={busy}
                  onClick={() => setDialog(null)}
                >
                  Cancel
                </button>
                <button
                  className={`button ${dialog.kind === 'delete' ? 'destructive' : 'primary'}`}
                  type="submit"
                  disabled={busy || (dialog.kind !== 'delete' && !name.trim())}
                >
                  {busy && <LoaderCircle className="spin" size={16} />}
                  {dialog.kind === 'delete'
                    ? 'Delete'
                    : dialog.kind === 'rename'
                      ? 'Save name'
                      : 'Create folder'}
                </button>
              </div>
            </>
          )}
        </form>
      )}
    </dialog>
  );
}
