import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, OnInit, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { getMockSettings, MockSettings, setMockSettings } from '../../core/mock/mock-settings';
import { UploadManager } from '../../core/upload/upload-manager';
import {
  CATEGORY_RULES,
  formatBytes,
  MANUAL_CATEGORIES,
  ManualCategory,
  OutageDetails,
  Zone,
  ZONES,
} from '../../core/upload/upload.models';
import { Topbar } from '../../shared/topbar/topbar';

/**
 * Pagina Upload (F4): alegi categoria permisă rolului, fișierul și, la avarii, zona,
 * momentul și stația. Video-urile merg în bucăți, cu reluare; vezi UploadManager.
 */
@Component({
  selector: 'app-upload',
  imports: [Topbar, DatePipe],
  templateUrl: './upload.html',
  styleUrl: './upload.scss',
})
export class Upload implements OnInit {
  private readonly auth = inject(AuthService);
  protected readonly manager = inject(UploadManager);

  protected readonly rules = CATEGORY_RULES;
  protected readonly zones = ZONES;
  protected readonly formatBytes = formatBytes;
  protected readonly useMocks = environment.useMocks;

  /** Doar categoriile permise rolului (matricea 4.4; ADMIN le vede pe toate). */
  protected readonly categories = computed(() => {
    this.auth.user();
    return MANUAL_CATEGORIES.filter((c) => this.auth.hasRole(...CATEGORY_RULES[c].roles));
  });

  protected readonly category = signal<ManualCategory | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly zone = signal<Zone | ''>('');
  protected readonly occurredAtLocal = signal(toLocalInput(new Date()));
  protected readonly stationId = signal('');
  protected readonly submitted = signal(false);

  protected readonly state = this.manager.state;
  protected readonly pending = this.manager.pending;

  protected readonly sizeError = computed(() => {
    const file = this.file();
    const category = this.category();
    if (!file || !category) {
      return null;
    }
    if (file.size === 0) {
      return 'Fișierul este gol.';
    }
    const max = CATEGORY_RULES[category].maxBytes;
    return file.size > max ? `Fișierul are ${formatBytes(file.size)}; maximul pentru categorie este ${formatBytes(max)}.` : null;
  });

  protected readonly occurredAtError = computed(() =>
    this.category() === 'OUTAGE_RECORD' && Number.isNaN(Date.parse(this.occurredAtLocal()))
      ? 'Alege momentul avariei.'
      : null,
  );

  /** Un video nou ar înlocui upload-ul neterminat; întâi trebuie continuat sau abandonat. */
  protected readonly blockedByPending = computed(() => this.category() === 'INSPECTION_VIDEO' && !!this.pending());

  protected readonly canSubmit = computed(
    () =>
      !!this.category() &&
      !!this.file() &&
      !this.sizeError() &&
      !this.manager.busy() &&
      !this.blockedByPending() &&
      (this.category() !== 'OUTAGE_RECORD' || (!!this.zone() && !this.occurredAtError())),
  );

  protected readonly percent = computed(() => {
    const s = this.state();
    return s.totalBytes > 0 ? Math.floor((s.sentBytes / s.totalBytes) * 100) : 0;
  });

  protected readonly eta = computed(() => {
    const s = this.state();
    if (!s.bytesPerSecond || s.bytesPerSecond <= 0) {
      return null;
    }
    const seconds = Math.ceil((s.totalBytes - s.sentBytes) / s.bytesPerSecond);
    return seconds >= 60 ? `${Math.floor(seconds / 60)} min ${seconds % 60} s` : `${seconds} s`;
  });

  protected readonly mockSettings = signal<MockSettings>(getMockSettings());

  constructor() {
    // Prima categorie permisă e selectată implicit.
    effect(() => {
      const allowed = this.categories();
      const current = this.category();
      if (!current || !allowed.includes(current)) {
        this.category.set(allowed[0] ?? null);
      }
    });
  }

  ngOnInit(): void {
    void this.manager.checkPending();
  }

  protected selectCategory(category: ManualCategory): void {
    this.category.set(category);
    this.submitted.set(false);
  }

  protected onFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
    this.submitted.set(false);
  }

  /** Re-selectarea fișierului după refresh: UploadManager verifică numele și mărimea, apoi reia. */
  protected onResumeFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      void this.manager.resume(file);
    }
  }

  protected submit(): void {
    this.submitted.set(true);
    const category = this.category();
    const file = this.file();
    if (!this.canSubmit() || !category || !file) {
      return;
    }
    let outage: OutageDetails | null = null;
    if (category === 'OUTAGE_RECORD') {
      outage = {
        zone: this.zone() as Zone,
        occurredAt: toIsoUtc(this.occurredAtLocal()),
        stationId: this.stationId().trim() || null,
      };
    }
    void this.manager.start(file, category, outage);
  }

  protected newUpload(): void {
    this.manager.reset();
    this.file.set(null);
    this.submitted.set(false);
    this.occurredAtLocal.set(toLocalInput(new Date()));
  }

  protected updateMock(change: Partial<MockSettings>): void {
    const next = { ...this.mockSettings(), ...change };
    this.mockSettings.set(next);
    setMockSettings(next);
  }

  protected phaseLabel(): string {
    switch (this.state().phase) {
      case 'uploading':
        return 'Se încarcă';
      case 'retrying':
        return 'Se reîncearcă';
      case 'completing':
        return 'Se finalizează (hash și metadate)';
      case 'paused':
        return 'În pauză';
      case 'done':
        return 'Încărcat';
      case 'error':
        return 'Eroare';
      default:
        return '';
    }
  }
}

/** Valoarea pentru <input type="datetime-local"> (ora locală, fără secunde). */
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Ora locală din formular → ISO-8601 UTC, ca în contract (2026-11-14T09:32:00Z). */
function toIsoUtc(local: string): string {
  return new Date(local).toISOString().replace(/\.\d{3}Z$/, 'Z');
}
