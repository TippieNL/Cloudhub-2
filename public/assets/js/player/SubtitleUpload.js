/**
 * "Add subtitles…" from inside the player.
 *
 * The file menu in the main app could already do this, but the player is where
 * you notice a film has no subtitles -- and its subtitles button used to hide
 * itself for exactly those films, leaving nowhere to start. For accounts that
 * may upload, the button now stays and its menu ends with this action.
 *
 * Same rules as the main app (addSubtitles() in app.js): a .srt or .vtt of at
 * most 4 MB, put beside the video under the name the players look for
 * (Holiday.nl.srt), replacing one of that name after asking -- to the trash,
 * like any delete. Once it has landed the track list is read again and the new
 * track turned on, without reloading the page or losing the place.
 *
 * The player page is standalone and does not carry the app's CSRF token, so
 * this asks /api/auth/status for one, which is also how it learns the role.
 */

const EXTENSIONS = new Set(['srt', 'vtt']);
/** Kept in step with MAX_BYTES in src/Services/SubtitleService.php. */
const MAX_BYTES = 4194304;

/** The language a downloaded subtitle's own name claims, if it looks like one. */
export function guessLanguage(fileName) {
    const parts = fileName.replace(/\.[^.]+$/, '').split('.');
    const last = (parts.length > 1 ? parts[parts.length - 1] : '').toLowerCase();
    return /^[a-z]{2,3}$/.test(last) ? last : '';
}

/** The name a subtitle has to have for the players to find it. */
export function subtitleFileName(videoPath, language, extension) {
    const stem = (videoPath.split('/').pop() || '').replace(/\.[^.]+$/, '');
    return language ? `${stem}.${language}.${extension}` : `${stem}.${extension}`;
}

export class SubtitleUpload {
    constructor(videoPath) {
        this.videoPath = videoPath;
        this.csrf = '';
        const front = window.CLOUDHUB_FRONT || '/';
        this.url = (route, query = '') => `${front}?route=${encodeURIComponent(route)}${query}`;
    }

    /** Whether this account may add a file. Asked once; a failure means no. */
    async allowed() {
        if (!this.videoPath) return false;
        try {
            const status = await (await fetch(this.url('/api/auth/status'), { credentials: 'same-origin' })).json();
            this.csrf = status.csrfToken || '';
            return ['editor', 'admin'].includes(status.user?.role);
        } catch {
            return false;
        }
    }

    async request(route, { method = 'GET', body, query = '', raw = false } = {}) {
        const headers = {};
        if (method !== 'GET') headers['X-CSRF-Token'] = this.csrf;
        if (body !== undefined && !raw) headers['Content-Type'] = 'application/json';
        const response = await fetch(this.url(route, query), {
            method, headers, credentials: 'same-origin',
            body: body === undefined ? undefined : (raw ? body : JSON.stringify(body)),
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.error?.message || `HTTP ${response.status}`);
        return data;
    }

    async tracks() {
        const found = await this.request('/api/files/subtitles', { query: `&path=${encodeURIComponent(this.videoPath)}` });
        return Array.isArray(found.tracks) ? found.tracks : [];
    }

    pickFile() {
        return new Promise((resolve) => {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.srt,.vtt,text/vtt,application/x-subrip';
            input.hidden = true;
            const done = (file) => { input.remove(); resolve(file); };
            input.addEventListener('change', () => done(input.files?.[0] || null), { once: true });
            input.addEventListener('cancel', () => done(null), { once: true });
            document.body.appendChild(input);
            input.click();
        });
    }

    /**
     * The whole flow. Resolves to the new track list and the added file's name,
     * or null when it was abandoned; throws with a message to show otherwise.
     */
    async add() {
        const file = await this.pickFile();
        if (!file) return null;

        const extension = (file.name.includes('.') ? file.name.split('.').pop() : '').toLowerCase();
        if (!EXTENSIONS.has(extension)) throw new Error('Subtitles have to be a .srt or .vtt file');
        // It would upload and then never appear in the menu.
        if (file.size > MAX_BYTES) throw new Error('That file is too large to be a subtitle track');

        const answer = window.prompt(
            'Language code for these subtitles — en, nl, de… (leave empty for none)',
            guessLanguage(file.name) || 'en',
        );
        if (answer === null) return null;
        const language = answer.trim().toLowerCase();
        // A tag, not a path: no dots (each would be read as another tag) and no slashes.
        if (language && !/^[a-z0-9-]{1,20}$/.test(language)) {
            throw new Error('Use a short code such as en or nl, without dots or spaces');
        }

        const name = subtitleFileName(this.videoPath, language, extension);
        const folder = this.videoPath.substring(0, this.videoPath.lastIndexOf('/')) || '/';

        const existing = await this.tracks().catch(() => []);
        const clash = existing.find((track) => (track.name || '').toLowerCase() === name.toLowerCase());
        if (clash) {
            if (!window.confirm(`${clash.label || name} subtitles are already there. Replace them? The current file goes to the trash.`)) {
                return null;
            }
            await this.request('/api/files/delete', { method: 'DELETE', body: { path: clash.path } });
        }

        // One chunk: a subtitle is smaller than one by definition.
        const started = await this.request('/api/uploads/init', {
            method: 'POST',
            body: {
                targetPath: folder, name, size: file.size, conflict: 'reject',
                uploadId: 'sub' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
            },
        });
        const chunk = await fetch(`${this.url('/api/uploads/chunk')}&id=${encodeURIComponent(started.id)}`, {
            method: 'PUT',
            credentials: 'same-origin',
            headers: { 'X-CSRF-Token': this.csrf, 'X-Upload-Offset': '0', 'Content-Type': 'application/octet-stream' },
            body: file,
        });
        if (!chunk.ok) {
            const data = await chunk.json().catch(() => ({}));
            throw new Error(data.error?.message || 'The upload failed');
        }
        await this.request('/api/uploads/complete', { method: 'POST', body: { id: started.id } });

        return { tracks: await this.tracks(), name };
    }
}
