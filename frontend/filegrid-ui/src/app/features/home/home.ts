import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Topbar } from '../../shared/topbar/topbar';

/**
 * Pagina protejată de după login. De aici se ajunge la Upload (F4), la Căutare și descărcare (F5)
 * și, pentru DISPATCH și ADMIN, la Statistici (F6).
 */
@Component({
  selector: 'app-home',
  imports: [Topbar, RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly auth = inject(AuthService);
}
