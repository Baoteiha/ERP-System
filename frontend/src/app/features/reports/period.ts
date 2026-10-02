import { DailySales } from '../../core/models';

/** Report days are cut in Vietnam time (the server's default `app.reporting.zone`, which has no DST). */
const REPORT_UTC_OFFSET = '+07:00';
const REPORT_UTC_OFFSET_HOURS = 7;
const DAY_MS = 86_400_000;

/** Calendar date (yyyy-mm-dd) of an instant in the reporting zone. */
function reportDay(instant: number): string {
  return new Date(instant + REPORT_UTC_OFFSET_HOURS * 3_600_000).toISOString().slice(0, 10);
}

/** The last `count` report days ending today, oldest first, plus the instant bounds covering them. */
export function reportPeriod(now: number, count: number): { days: string[]; from: Date; to: Date } {
  const days: string[] = [];
  for (let i = count - 1; i >= 0; i--) days.push(reportDay(now - i * DAY_MS));
  const from = new Date(`${days[0]}T00:00:00${REPORT_UTC_OFFSET}`);
  const to = new Date(new Date(`${days[days.length - 1]}T00:00:00${REPORT_UTC_OFFSET}`).getTime() + DAY_MS);
  return { days, from, to };
}

/** One row per day in `days`; a day with no sales is a zero row, so a quiet day shows as a gap in the bars. */
export function zeroFillDays(days: string[], sales: DailySales[]): DailySales[] {
  const byDay = new Map(sales.map((d) => [d.day, d]));
  return days.map((day) => byDay.get(day) ?? { day, orderCount: 0, netSales: '0' });
}
