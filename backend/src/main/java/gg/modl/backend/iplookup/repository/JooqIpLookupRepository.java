package gg.modl.backend.iplookup.repository;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.IpLookupsRecord;
import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqIpLookupRepository implements IpLookupRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final Upserts upserts;

    @Override
    public Optional<IpLookup> find(String ip) {
        if (!TextLimits.canMatch(IP_LOOKUPS.IP, ip)) {
            return Optional.empty();
        }
        return unitOfWork.read(() -> dsl.selectFrom(IP_LOOKUPS)
            .where(IP_LOOKUPS.IP.eq(ip))
            .fetchOptional()
            .flatMap(JooqIpLookupRepository::toLookup));
    }

    @Override
    public void save(IpLookup lookup) {
        IpFacts facts = lookup.facts();
        IpLookupsRecord row = new IpLookupsRecord(
            TextLimits.require(IP_LOOKUPS.IP, lookup.ip()),
            TextLimits.require(IP_LOOKUPS.PROVIDER, lookup.provider().id()),
            fit(IP_LOOKUPS.COUNTRY, facts.country()),
            fit(IP_LOOKUPS.REGION, facts.region()),
            fit(IP_LOOKUPS.ASN, facts.asn()),
            TextLimits.require(IP_LOOKUPS.VPN, facts.vpn().name()),
            facts.hosting(),
            lookup.lookedUpAt());
        Map<Field<?>, Object> assignments = new HashMap<>();
        assignments.put(IP_LOOKUPS.PROVIDER, row.getProvider());
        assignments.put(IP_LOOKUPS.COUNTRY, row.getCountry());
        assignments.put(IP_LOOKUPS.REGION, row.getRegion());
        assignments.put(IP_LOOKUPS.ASN, row.getAsn());
        assignments.put(IP_LOOKUPS.VPN, row.getVpn());
        assignments.put(IP_LOOKUPS.IS_HOSTING, row.getIsHosting());
        assignments.put(IP_LOOKUPS.LOOKED_UP_AT, row.getLookedUpAt());
        unitOfWork.write(() -> upserts.onPrimaryKey(IP_LOOKUPS, row, assignments));
    }

    @Override
    public long count() {
        return unitOfWork.read(() -> dsl.fetchCount(IP_LOOKUPS));
    }

    @Override
    public int deleteOldest(int batchSize) {
        return deleteOldestWhere(DSL.noCondition(), batchSize);
    }

    @Override
    public int deleteLookedUpBefore(Instant cutoff, int batchSize) {
        return deleteOldestWhere(IP_LOOKUPS.LOOKED_UP_AT.lt(cutoff), batchSize);
    }

    private int deleteOldestWhere(Condition condition, int batchSize) {
        return unitOfWork.write(() -> {
            List<String> oldest = dsl.select(IP_LOOKUPS.IP)
                .from(IP_LOOKUPS)
                .where(condition)
                .orderBy(IP_LOOKUPS.LOOKED_UP_AT, IP_LOOKUPS.IP)
                .limit(batchSize)
                .fetch(IP_LOOKUPS.IP);
            return InClauseChunks.sum(oldest, chunk -> dsl.deleteFrom(IP_LOOKUPS)
                .where(IP_LOOKUPS.IP.in(chunk))
                .execute());
        });
    }

    private static Optional<IpLookup> toLookup(IpLookupsRecord row) {
        return IpLookupProvider.ofId(row.getProvider()).map(provider -> new IpLookup(
            row.getIp(),
            provider,
            new IpFacts(row.getCountry(), row.getRegion(), row.getAsn(), VpnStatus.fromStored(row.getVpn()),
                Boolean.TRUE.equals(row.getIsHosting())),
            row.getLookedUpAt()));
    }

    @Nullable
    private static String fit(TableField<?, String> field, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return TextLimits.require(field, CappedText.of(StorableText.strip(value), TextLimits.limit(field)));
    }
}
