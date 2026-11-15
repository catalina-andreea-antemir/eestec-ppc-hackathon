import { Sha256, sha256Hex } from './sha256';

describe('Sha256 (mock)', () => {
  it('dă același hash ca crypto.subtle, oricum ar fi împărțite datele', async () => {
    const data = new Uint8Array(1000).map((_, i) => (i * 31 + 7) & 0xff);
    const expected = await sha256Hex(data);

    for (const split of [[1000], [1, 999], [63, 64, 65, 808], [500, 500], [56, 944]]) {
      let hash = new Sha256();
      let offset = 0;
      for (const size of split) {
        hash.update(data.subarray(offset, offset + size));
        offset += size;
        // The state is saved and reloaded between chunks, as in sessionStorage.
        hash = new Sha256(JSON.parse(JSON.stringify(hash.state())));
      }
      expect(hash.hex()).toBe(expected);
    }
  });

  it('hash-ul șirului gol e cel standard', () => {
    expect(new Sha256().hex()).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  });
});
