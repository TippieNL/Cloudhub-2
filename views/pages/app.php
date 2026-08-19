<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>File Server</title>
    <link rel="icon" href="<?= htmlspecialchars($basePath, ENT_QUOTES) ?>/public/favicon.png">
    <link rel="stylesheet" href="<?= htmlspecialchars($basePath, ENT_QUOTES) ?>/public/assets/css/app.css?v=<?= (int)@filemtime(dirname(__DIR__, 2).'/public/assets/css/app.css') ?>">
    <link rel="stylesheet" href="<?= htmlspecialchars($basePath, ENT_QUOTES) ?>/public/assets/css/player.css">
</head>
<body>
    <div id="login" class="overlay">
        <form id="login-form" class="dialog">
            <h2>File Server</h2>
            <p>Enter your credentials to access the file server.</p>
            <label>Username<input id="username" autocomplete="username" required></label>
            <label>Password<input id="password" type="password" autocomplete="current-password" required></label>
            <p id="login-error" class="error"></p>
            <button>Sign In</button>
        </form>
    </div>
    <header>
        <strong>◉ File Server</strong>
        <nav>
            <a href="<?= htmlspecialchars($frontController, ENT_QUOTES) ?>" data-route="/">Files</a>
            <a href="<?= htmlspecialchars($frontController, ENT_QUOTES) ?>?route=%2Fservers" data-route="/servers">Servers</a>
            <a href="<?= htmlspecialchars($frontController, ENT_QUOTES) ?>?route=%2Fbrowse" data-route="/browse">Browse</a>
        </nav>
        <div>
            <button id="theme">◐</button>
            <button id="logout">Log out</button>
        </div>
    </header>
    <main>
        <section id="files-page">
            <div class="file-toolbar">
                <div id="breadcrumbs" class="breadcrumbs" aria-label="Current folder"></div>
                <div class="toolbar-actions">
                    <button id="mkdir">New folder</button>
                    <button id="upload-btn" class="primary-button" type="button">Upload</button>
                    <button id="zip">Download selected</button>
                    <button id="delete-selected" type="button">Delete selected</button>
                    <button id="refresh">Refresh</button>
                </div>
            </div>
            <div class="file-controls">
                <input id="search" type="search" placeholder="Search files">
                <select id="sort-files" aria-label="Sort files">
                    <option value="name-asc">Name A–Z</option>
                    <option value="name-desc">Name Z–A</option>
                    <option value="date-desc">Newest</option>
                    <option value="date-asc">Oldest</option>
                    <option value="size-desc">Largest</option>
                    <option value="size-asc">Smallest</option>
                </select>
                <div class="view-switch" aria-label="File view">
                    <button id="grid-view" type="button" title="Grid view">▦</button>
                    <button id="list-view" type="button" title="List view">☷</button>
                </div>
            </div>
            <div id="selection-bar" class="selection-bar" hidden>
                <strong id="selection-count">0 selected</strong>
                <button id="select-all" type="button">Select all</button>
                <button id="clear-selection" type="button">Clear</button>
                <button id="selection-download" type="button">Download</button>
                <button id="selection-delete" type="button">Delete</button>
            </div>
            <div id="file-list" class="file-grid" aria-live="polite"></div>
        </section>

        <!-- Upload dialog -->
        <div id="upload-overlay" class="modal-overlay" hidden>
            <form id="upload-form" class="upload-dialog" novalidate>
                <div class="modal-heading">
                    <div>
                        <h2>Upload files</h2>
                        <p>Upload one or more files to <strong id="upload-target">Root</strong>.</p>
                    </div>
                    <button id="upload-close" class="icon-button" type="button" aria-label="Close upload dialog">×</button>
                </div>
                <label id="upload-dropzone" class="file-picker upload-dropzone" for="upload-input">
                    <span class="file-picker-title">Drop files here or choose files</span>
                    <span class="file-picker-help">Select up to <?= (int)$config['max_upload_files'] ?> files. Maximum <?= (int)$config['max_upload_mb'] >= 1024 ? number_format($config['max_upload_mb']/1024, 0).' GB' : (int)$config['max_upload_mb'].' MB' ?> per file. Large files are uploaded in resumable chunks.</span>
                    <input id="upload-input" name="files[]" type="file" multiple>
                </label>
                <div id="upload-selection" class="upload-selection" aria-live="polite">No files selected.</div>
                <div id="upload-queue" class="upload-queue" aria-live="polite"></div>
                <label class="upload-conflict">If a filename already exists
                    <select id="upload-conflict">
                        <option value="rename">Keep both (rename new file)</option>
                        <option value="overwrite">Replace existing file</option>
                        <option value="reject">Stop and report conflict</option>
                    </select>
                </label>
                <div id="upload-progress-wrap" class="upload-progress-wrap" hidden>
                    <div class="progress-header">
                        <span id="upload-progress-label">Preparing upload…</span>
                        <strong id="upload-progress-percent">0%</strong>
                    </div>
                    <progress id="upload-progress" max="100" value="0">0%</progress>
                    <div id="upload-progress-bytes" class="muted"></div>
                </div>
                <div id="upload-message" class="status-message" role="status" aria-live="polite" hidden></div>
                <div class="modal-actions">
                    <button id="upload-cancel" type="button">Cancel</button>
                    <button id="upload-submit" class="primary-button" type="submit" disabled>Upload</button>
                </div>
            </form>
        </div>

        <div id="confirm-overlay" class="modal-overlay" hidden>
            <form id="confirm-dialog" class="confirm-dialog">
                <h2 id="confirm-title">Confirm action</h2>
                <p id="confirm-message"></p>
                <div class="modal-actions">
                    <button id="confirm-cancel" type="button">Cancel</button>
                    <button id="confirm-ok" class="danger-button" type="submit">Confirm</button>
                </div>
            </form>
        </div>
        <div id="input-overlay" class="modal-overlay" hidden>
            <form id="input-dialog" class="confirm-dialog">
                <h2 id="input-title">Enter value</h2>
                <label id="input-label">Name<input id="dialog-input" required></label>
                <div class="modal-actions">
                    <button id="input-cancel" type="button">Cancel</button>
                    <button class="primary-button" type="submit">Save</button>
                </div>
            </form>
        </div>
        <div id="file-context" class="context-menu" hidden role="menu"></div>

        <section id="servers-page" hidden>
            <div class="toolbar">
                <h2>Storage servers</h2>
                <button id="add-server">Add server</button>
            </div>
            <div id="server-list"></div>
            <form id="server-form" class="panel" hidden>
                <h3>Server</h3>
                <input name="name" placeholder="Name" required>
                <select name="type">
                    <option>local</option>
                    <option>ftp</option>
                    <option>sftp</option>
                    <option>smb</option>
                    <option>http_api</option>
                </select>
                <textarea name="config" rows="8" placeholder='{"basePath":"/srv/files"}' required></textarea>
                <label><input type="checkbox" name="isActive" checked> Active</label>
                <label><input type="checkbox" name="isDefault"> Default</label>
                <button>Save</button>
                <button type="button" id="cancel-server">Cancel</button>
            </form>
        </section>
        <section id="browse-page" hidden>
            <h2>Remote server browser</h2>
            <p>The PHP migration preserves server configuration and upload targets. Remote browsing depends on the corresponding PHP extension/client being installed.</p>
            <div id="active-servers"></div>
        </section>
    </main>

<?php 
if (isset($mediaFile)) {
    include __DIR__ . '/player_component.php';
}
?>

    <div id="toast"></div>

    <script type="module" nonce="<?= htmlspecialchars(\CloudHub\Services\Security::cspNonce(), ENT_QUOTES) ?>" src="<?= htmlspecialchars($basePath, ENT_QUOTES) ?>/public/assets/js/player/PlayerUI.js"></script>
    <script nonce="<?= htmlspecialchars(\CloudHub\Services\Security::cspNonce(), ENT_QUOTES) ?>">
        window.CLOUDHUB_BASE = <?= json_encode($basePath, JSON_UNESCAPED_SLASHES) ?>;
        window.CLOUDHUB_FRONT = <?= json_encode($frontController, JSON_UNESCAPED_SLASHES) ?>;
        window.CLOUDHUB_ROUTE = <?= json_encode($path, JSON_UNESCAPED_SLASHES) ?>;
        window.CLOUDHUB_UPLOAD_LIMITS = <?= json_encode([
            'maxFiles' => $config['max_upload_files'],
            'maxMb' => $config['max_upload_mb'],
            'chunkMb' => $config['upload_chunk_mb'],
            'retryCount' => $config['upload_retry_count'],
            'conflict' => $config['upload_conflict']
        ], JSON_UNESCAPED_SLASHES) ?>;
    </script>
    <script src="<?= htmlspecialchars($basePath, ENT_QUOTES) ?>/public/assets/js/app.js?v=<?= (int)@filemtime(dirname(__DIR__, 2).'/public/assets/js/app.js') ?>"></script>
</body>
</html>
