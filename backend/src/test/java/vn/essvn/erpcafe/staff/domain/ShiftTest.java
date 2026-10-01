package vn.essvn.erpcafe.staff.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: ShiftTest
 * Purpose: Checks the shift state machine: every illegal transition is refused and labour cost rounds HALF_EVEN.
 */
class ShiftTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final Instant NOW = START.plus(Duration.ofDays(2));
    private static final Duration MAX = Duration.ofHours(16);
    private static final Money RATE = Money.of(new BigDecimal("30000"));

    private static Shift scheduled() {
        return new Shift(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START, START.plus(Duration.ofHours(8)), null);
    }

    private static Shift inProgress() {
        Shift s = scheduled();
        s.clockIn(START, RATE);
        return s;
    }

    private static Shift completed() {
        Shift s = inProgress();
        s.clockOut(START.plus(Duration.ofHours(8)), MAX);
        return s;
    }

    private static Shift cancelled() {
        Shift s = scheduled();
        s.cancel();
        return s;
    }

    private static void refused(Shift s, Consumer<Shift> action) {
        assertThatThrownBy(() -> action.accept(s)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void scheduledShift_cannotClockOutOrBeAdjusted() {
        refused(scheduled(), s -> s.clockOut(START.plusSeconds(60), MAX));
        refused(scheduled(), s -> s.adjust(null, START.plusSeconds(60), MAX, NOW));
    }

    @Test
    void inProgressShift_cannotClockInRescheduleOrCancel() {
        refused(inProgress(), s -> s.clockIn(START, RATE));
        refused(inProgress(), s -> s.reschedule(START, START.plusSeconds(60), null));
        refused(inProgress(), Shift::cancel);
    }

    @Test
    void completedShift_cannotClockInOutRescheduleOrCancel() {
        refused(completed(), s -> s.clockIn(START, RATE));
        refused(completed(), s -> s.clockOut(START.plusSeconds(60), MAX));
        refused(completed(), s -> s.reschedule(START, START.plusSeconds(60), null));
        refused(completed(), Shift::cancel);
    }

    @Test
    void cancelledShift_cannotDoAnything() {
        refused(cancelled(), s -> s.clockIn(START, RATE));
        refused(cancelled(), s -> s.clockOut(START.plusSeconds(60), MAX));
        refused(cancelled(), s -> s.reschedule(START, START.plusSeconds(60), null));
        refused(cancelled(), Shift::cancel);
        refused(cancelled(), s -> s.adjust(null, START.plusSeconds(60), MAX, NOW));
    }

    @Test
    void clockOut_mustBeAfterClockInAndWithinTheMaximum() {
        refused(inProgress(), s -> s.clockOut(START, MAX));
        refused(inProgress(), s -> s.clockOut(START.plus(Duration.ofHours(17)), MAX));
        assertThat(completed().getStatus()).isEqualTo(ShiftStatus.COMPLETED);
    }

    @Test
    void adjust_canCorrectAnOpenOrCompletedShiftAndKeepsTheSnapshotRate() {
        Shift open = inProgress();
        open.adjust(null, START.plus(Duration.ofHours(4)), MAX, NOW);
        assertThat(open.getStatus()).isEqualTo(ShiftStatus.COMPLETED);
        assertThat(open.getLaborCost().getAmount()).isEqualByComparingTo("120000");

        Shift done = completed();
        done.adjust(START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(3)), MAX, NOW);
        assertThat(done.getLaborCost().getAmount()).isEqualByComparingTo("60000");
        assertThat(done.getHourlyRate()).isEqualTo(RATE);
        Shift longer = inProgress();
        longer.adjust(null, START.plus(Duration.ofHours(17)), Duration.ofHours(24), NOW); // beyond the clock-out limit
        assertThat(longer.getLaborCost().getAmount()).isEqualByComparingTo("510000");
        refused(inProgress(), s -> s.adjust(null, START.plus(Duration.ofHours(25)), Duration.ofHours(24), NOW));
        refused(inProgress(), s -> s.adjust(null, NOW.plusSeconds(60), Duration.ofHours(24), NOW));
        refused(inProgress(), s -> s.adjust(null, START, MAX, NOW));
    }

    @Test
    void laborCost_roundsHalfEvenAtTheMoneyScale() {
        Shift oneSecond = new Shift(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START, START.plusSeconds(3600), null);
        oneSecond.clockIn(START, Money.of(new BigDecimal("0.18")));
        oneSecond.clockOut(START.plusSeconds(1), MAX); // 0.18 / 3600 = 0.00005 exactly: tie goes to even
        assertThat(oneSecond.getLaborCost().getAmount()).isEqualByComparingTo("0.0000");

        Shift threeSeconds = new Shift(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START, START.plusSeconds(3600), null);
        threeSeconds.clockIn(START, Money.of(new BigDecimal("0.18")));
        threeSeconds.clockOut(START.plusSeconds(3), MAX); // 0.00015: tie goes to 0.0002
        assertThat(threeSeconds.getLaborCost().getAmount()).isEqualByComparingTo("0.0002");
    }
}
