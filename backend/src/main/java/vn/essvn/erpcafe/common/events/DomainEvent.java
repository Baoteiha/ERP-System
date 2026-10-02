package vn.essvn.erpcafe.common.events;

import java.util.UUID;

import org.springframework.core.ResolvableType;
import org.springframework.core.ResolvableTypeProvider;

/**
 * Class: DomainEvent
 * Purpose: Hands a delivered event to its listeners with the id it was recorded under, so each listener can apply it once.
 */
public record DomainEvent<T>(UUID id, UUID companyId, T payload) implements ResolvableTypeProvider {

    // Lets an @EventListener take DomainEvent<OrderCompleted> and receive only those.
    @Override
    public ResolvableType getResolvableType() {
        return ResolvableType.forClassWithGenerics(DomainEvent.class, ResolvableType.forInstance(payload));
    }
}
