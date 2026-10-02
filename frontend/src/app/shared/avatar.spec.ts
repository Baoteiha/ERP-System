import { initialsOf } from './avatar';

describe('initialsOf', () => {
  it('takes the first letters of the first two words', () => expect(initialsOf('Nguyễn Văn An')).toBe('NV'));
  it('takes two letters of a single word', () => expect(initialsOf('admin@example.com')).toBe('AD'));
  it('is a placeholder for an empty name', () => expect(initialsOf('  ')).toBe('?'));
});
