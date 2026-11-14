import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { toApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly useMocks = environment.useMocks;
  protected readonly submitting = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.errorMessage.set(loginErrorMessage(err));
      },
    });
  }
}

function loginErrorMessage(err: unknown): string {
  const status = err instanceof HttpErrorResponse ? err.status : 0;
  if (status === 401) {
    return 'Utilizator sau parolă greșită.';
  }
  if (status === 429 || status === 503) {
    const seconds = toApiError(err)?.retryAfterSeconds;
    return seconds
      ? `Serverul e ocupat. Încearcă din nou peste ${seconds} s.`
      : 'Serverul e ocupat. Încearcă din nou în câteva secunde.';
  }
  if (status === 0) {
    return 'Serverul nu răspunde. Verifică dacă sistemul e pornit.';
  }
  // Alte erori: mesajul în română din contract (câmpul `message`), dacă există.
  return toApiError(err)?.message ?? 'A apărut o eroare. Încearcă din nou.';
}
