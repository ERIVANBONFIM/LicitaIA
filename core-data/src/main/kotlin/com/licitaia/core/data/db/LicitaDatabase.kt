package com.licitaia.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CompanyEntity::class, UserEntity::class, RadarEntity::class, OpportunityEntity::class,
        TenderEntity::class, TenderAnalysisEntity::class, DocumentEntity::class, ProposalEntity::class,
        PortalSessionEntity::class, LiveSessionEntity::class, BidEventEntity::class, NotificationEntity::class,
        AuditEventEntity::class, AiConfigEntity::class, MessageEntity::class, CompetitionEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class LicitaDatabase : RoomDatabase() {
    abstract fun companyDao(): CompanyDao
    abstract fun userDao(): UserDao
    abstract fun radarDao(): RadarDao
    abstract fun opportunityDao(): OpportunityDao
    abstract fun tenderDao(): TenderDao
    abstract fun tenderAnalysisDao(): TenderAnalysisDao
    abstract fun documentDao(): DocumentDao
    abstract fun proposalDao(): ProposalDao
    abstract fun portalSessionDao(): PortalSessionDao
    abstract fun liveSessionDao(): LiveSessionDao
    abstract fun bidEventDao(): BidEventDao
    abstract fun notificationDao(): NotificationDao
    abstract fun auditDao(): AuditDao
    abstract fun aiConfigDao(): AiConfigDao
    abstract fun messageDao(): MessageDao
    abstract fun competitionDao(): CompetitionDao

    companion object {
        const val NAME = "licitaia.db"
    }
}
