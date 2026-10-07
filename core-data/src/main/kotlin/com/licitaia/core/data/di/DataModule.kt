package com.licitaia.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.licitaia.core.ai.AiSettingsSource
import com.licitaia.core.data.crypto.DatabaseKeyProvider
import com.licitaia.core.data.crypto.EncryptedOpenHelperFactory
import com.licitaia.core.data.db.AiConfigDao
import com.licitaia.core.data.db.AuditDao
import com.licitaia.core.data.db.BidEventDao
import com.licitaia.core.data.db.CompanyDao
import com.licitaia.core.data.db.CompetitionDao
import com.licitaia.core.data.db.DocumentDao
import com.licitaia.core.data.db.LicitaDatabase
import com.licitaia.core.data.db.LiveSessionDao
import com.licitaia.core.data.db.MessageDao
import com.licitaia.core.data.db.NotificationDao
import com.licitaia.core.data.db.OpportunityDao
import com.licitaia.core.data.db.PortalSessionDao
import com.licitaia.core.data.db.ProposalDao
import com.licitaia.core.data.db.RadarDao
import com.licitaia.core.data.db.TenderAnalysisDao
import com.licitaia.core.data.db.TenderDao
import com.licitaia.core.data.db.UserDao
import com.licitaia.core.data.repository.AiConfigRepositoryImpl
import com.licitaia.core.data.repository.AiSettingsSourceImpl
import com.licitaia.core.data.repository.AuditRepositoryImpl
import com.licitaia.core.data.repository.AuthRepositoryImpl
import com.licitaia.core.data.repository.CompanyRepositoryImpl
import com.licitaia.core.data.repository.CompetitionRepositoryImpl
import com.licitaia.core.data.repository.DocumentRepositoryImpl
import com.licitaia.core.data.repository.LiveSessionStoreImpl
import com.licitaia.core.data.repository.MessageRepositoryImpl
import com.licitaia.core.data.repository.NotificationRepositoryImpl
import com.licitaia.core.data.repository.OpportunityRepositoryImpl
import com.licitaia.core.data.repository.PortalRepositoryImpl
import com.licitaia.core.data.repository.ProposalRepositoryImpl
import com.licitaia.core.data.repository.RadarRepositoryImpl
import com.licitaia.core.data.repository.TenderRepositoryImpl
import com.licitaia.core.data.settings.SettingsRepositoryImpl
import com.licitaia.domain.live.LiveSessionStore
import com.licitaia.domain.repository.AiConfigRepository
import com.licitaia.domain.repository.AuditRepository
import com.licitaia.domain.repository.AuthRepository
import com.licitaia.domain.repository.CompanyRepository
import com.licitaia.domain.repository.CompetitionRepository
import com.licitaia.domain.repository.DocumentRepository
import com.licitaia.domain.repository.MessageRepository
import com.licitaia.domain.repository.NotificationRepository
import com.licitaia.domain.repository.OpportunityRepository
import com.licitaia.domain.repository.PortalRepository
import com.licitaia.domain.repository.ProposalRepository
import com.licitaia.domain.repository.RadarRepository
import com.licitaia.domain.repository.SettingsRepository
import com.licitaia.domain.repository.TenderRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {

    /**
     * Banco cifrado com SQLCipher (chave no Keystore via [DatabaseKeyProvider]). A migração do banco em texto puro
     * existente acontece dentro da factory, na primeira abertura, antes de qualquer acesso do Room.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, keyProvider: DatabaseKeyProvider): LicitaDatabase =
        Room.databaseBuilder(context, LicitaDatabase::class.java, LicitaDatabase.NAME)
            .openHelperFactory(EncryptedOpenHelperFactory(context, keyProvider))
            .addMigrations(*com.licitaia.core.data.db.DatabaseMigrations.ALL)
            .build()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("licitaia_prefs") })

    @Provides
    @Singleton
    @DataScope
    fun provideDataScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides fun companyDao(db: LicitaDatabase): CompanyDao = db.companyDao()
    @Provides fun userDao(db: LicitaDatabase): UserDao = db.userDao()
    @Provides fun radarDao(db: LicitaDatabase): RadarDao = db.radarDao()
    @Provides fun opportunityDao(db: LicitaDatabase): OpportunityDao = db.opportunityDao()
    @Provides fun tenderDao(db: LicitaDatabase): TenderDao = db.tenderDao()
    @Provides fun tenderAnalysisDao(db: LicitaDatabase): TenderAnalysisDao = db.tenderAnalysisDao()
    @Provides fun documentDao(db: LicitaDatabase): DocumentDao = db.documentDao()
    @Provides fun proposalDao(db: LicitaDatabase): ProposalDao = db.proposalDao()
    @Provides fun portalSessionDao(db: LicitaDatabase): PortalSessionDao = db.portalSessionDao()
    @Provides fun liveSessionDao(db: LicitaDatabase): LiveSessionDao = db.liveSessionDao()
    @Provides fun bidEventDao(db: LicitaDatabase): BidEventDao = db.bidEventDao()
    @Provides fun notificationDao(db: LicitaDatabase): NotificationDao = db.notificationDao()
    @Provides fun auditDao(db: LicitaDatabase): AuditDao = db.auditDao()
    @Provides fun aiConfigDao(db: LicitaDatabase): AiConfigDao = db.aiConfigDao()
    @Provides fun messageDao(db: LicitaDatabase): MessageDao = db.messageDao()
    @Provides fun competitionDao(db: LicitaDatabase): CompetitionDao = db.competitionDao()
    @Provides fun relevanceScoreDao(db: LicitaDatabase): com.licitaia.core.data.db.RelevanceScoreDao = db.relevanceScoreDao()
    @Provides fun comprasGovCacheDao(db: LicitaDatabase): com.licitaia.core.data.db.ComprasGovCacheDao = db.comprasGovCacheDao()
    @Provides fun portalRobotDao(db: LicitaDatabase): com.licitaia.core.data.db.PortalRobotDao = db.portalRobotDao()
    @Provides fun editalQuestionDao(db: LicitaDatabase): com.licitaia.core.data.db.EditalQuestionDao = db.editalQuestionDao()
    @Provides fun opportunityFlagDao(db: LicitaDatabase): com.licitaia.core.data.db.OpportunityFlagDao = db.opportunityFlagDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds abstract fun backupRepository(impl: com.licitaia.core.data.backup.CompanyBackupRepository): com.licitaia.domain.repository.BackupRepository
    @Binds abstract fun authRepository(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun companyRepository(impl: CompanyRepositoryImpl): CompanyRepository
    @Binds abstract fun radarRepository(impl: RadarRepositoryImpl): RadarRepository
    @Binds abstract fun opportunityRepository(impl: OpportunityRepositoryImpl): OpportunityRepository
    @Binds abstract fun tenderRepository(impl: TenderRepositoryImpl): TenderRepository
    @Binds abstract fun documentRepository(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds abstract fun proposalRepository(impl: ProposalRepositoryImpl): ProposalRepository
    @Binds abstract fun portalRepository(impl: PortalRepositoryImpl): PortalRepository
    @Binds abstract fun messageRepository(impl: MessageRepositoryImpl): MessageRepository
    @Binds abstract fun notificationRepository(impl: NotificationRepositoryImpl): NotificationRepository
    @Binds abstract fun auditRepository(impl: AuditRepositoryImpl): AuditRepository
    @Binds abstract fun competitionRepository(impl: CompetitionRepositoryImpl): CompetitionRepository
    @Binds abstract fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun aiConfigRepository(impl: AiConfigRepositoryImpl): AiConfigRepository
    @Binds abstract fun liveSessionStore(impl: LiveSessionStoreImpl): LiveSessionStore
    @Binds abstract fun aiSettingsSource(impl: AiSettingsSourceImpl): AiSettingsSource
    /** v11: "Minhas licitações" do Comprasnet e planos do robô de proposta/lance. */
    @Binds abstract fun portalRobotRepository(impl: com.licitaia.core.data.repository.PortalRobotRepositoryImpl): com.licitaia.domain.portal.PortalRobotRepository
    /** Cache persistente do Compras.gov.br usado pelo conector (connector-comprasgov não depende de core-data). */
    /** v13: "Pergunte ao edital" (histórico gravado) e aba "Itens" (itens oficiais com cache em memória). */
    @Binds abstract fun editalQuestionRepository(impl: com.licitaia.core.data.repository.EditalQuestionRepositoryImpl): com.licitaia.domain.repository.EditalQuestionRepository
    @Binds abstract fun tenderItemsRepository(impl: com.licitaia.core.data.repository.TenderItemsRepositoryImpl): com.licitaia.domain.repository.TenderItemsRepository
    /** v14: descartadas/vistas por empresa e selo "Nova". */
    @Binds abstract fun opportunityFlagsRepository(impl: com.licitaia.core.data.repository.OpportunityFlagsRepositoryImpl): com.licitaia.domain.repository.OpportunityFlagsRepository
    @Binds abstract fun listingRowStore(impl: com.licitaia.core.data.repository.RoomListingRowStore): com.licitaia.connector.api.ListingRowStore
}
