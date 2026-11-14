import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { Topbar } from '../../shared/topbar/topbar';

/**
 * Pagina protejată de după login. De aici se ajunge la Upload (F4) și la Căutare și descărcare (F5);
 * Dashboard (F6) vine într-un pas următor.
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
