import { Pipe, PipeTransform } from '@angular/core';
import { Money } from './models';

/** Format a Money DTO or numeric string as a localized currency amount. */
export function formatMoney(m: Money | string | number | null | undefined, currency = 'VND'): string {
  if (m === null || m === undefined) return '—';
  let amount: number;
  let ccy = currency;
  if (typeof m === 'object') { amount = Number(m.amount); ccy = m.currency || currency; }
  else { amount = Number(m); }
  if (Number.isNaN(amount)) return '—';
  try {
    return new Intl.NumberFormat(ccy === 'VND' ? 'vi-VN' : 'en-US', {
      style: 'currency', currency: ccy, maximumFractionDigits: ccy === 'VND' ? 0 : 2,
    }).format(amount);
  } catch {
    return `${amount.toLocaleString()} ${ccy}`;
  }
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: Money | string | number | null | undefined, currency = 'VND'): string {
    return formatMoney(value, currency);
  }
}

/** Formats a quantity with Vietnamese separators (28.240, 1,5); a dash when missing or not a number. */
@Pipe({ name: 'qty' })
export class QtyPipe implements PipeTransform {
  private static readonly fmt = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 3 });
  transform(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '—';
    const n = Number(value);
    return Number.isNaN(n) ? '—' : QtyPipe.fmt.format(n);
  }
}

/** Short id for display, e.g. #a1b2c3 */
@Pipe({ name: 'shortId' })
export class ShortIdPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return value ? '#' + value.slice(0, 6) : '—';
  }
}

/** Turns a SNAKE_CASE enum value into sentence case, e.g. DINE_IN to "Dine in". */
@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    if (!value) return '—';
    const words = value.toLowerCase().replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
  }
}
