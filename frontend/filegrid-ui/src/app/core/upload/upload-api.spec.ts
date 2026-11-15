import { scaleToFile } from './upload-api';

describe('scaleToFile (progresul la POST /files)', () => {
  it('corpul multipart mai mare decât fișierul nu trece de 100%', () => {
    // 1.36 KB sent out of a 1.36 KB body, for a 1.04 KB file
    expect(scaleToFile(1393, 1393, 1065)).toBe(1065);
  });

  it('raportează proporțional cu mărimea fișierului', () => {
    expect(scaleToFile(500, 1000, 800)).toBe(400);
  });

  it('fără mărimea corpului, plafonează la mărimea fișierului', () => {
    expect(scaleToFile(2000, undefined, 1500)).toBe(1500);
    expect(scaleToFile(700, undefined, 1500)).toBe(700);
  });
});
