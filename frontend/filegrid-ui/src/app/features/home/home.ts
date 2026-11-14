import { Component, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';

/**
 * Pagina protejată de după login. Deocamdată e goală; aici vor apărea
 * Upload (F4), Căutare și descărcare (F5) și Dashboard (F6).
 */
@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.scss',
})
export class Home {
  protected readonly auth = inject(AuthService);
}
