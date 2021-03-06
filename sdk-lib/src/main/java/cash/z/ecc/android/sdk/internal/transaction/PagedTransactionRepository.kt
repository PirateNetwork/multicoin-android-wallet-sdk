package cash.z.ecc.android.sdk.internal.transaction

import android.content.Context
import androidx.paging.PagedList
import androidx.room.Room
import androidx.room.RoomDatabase
import cash.z.ecc.android.sdk.db.entity.ConfirmedTransaction
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.SdkDispatchers
import cash.z.ecc.android.sdk.internal.SdkExecutors
import cash.z.ecc.android.sdk.internal.db.DerivedDataDb
import cash.z.ecc.android.sdk.internal.ext.android.toFlowPagedList
import cash.z.ecc.android.sdk.internal.ext.android.toRefreshable
import cash.z.ecc.android.sdk.internal.ext.tryWarn
import cash.z.ecc.android.sdk.internal.twig
import cash.z.ecc.android.sdk.jni.RustBackend
import cash.z.ecc.android.sdk.type.UnifiedViewingKey
import cash.z.ecc.android.sdk.type.WalletBirthday
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Example of a repository that leverages the Room paging library to return a [PagedList] of
 * transactions. Consumers can register as a page listener and receive an interface that allows for
 * efficiently paging data.
 *
 * @param pageSize transactions per page. This influences pre-fetch and memory configuration.
 */
class PagedTransactionRepository private constructor(
    private val db: DerivedDataDb,
    private val pageSize: Int
) : TransactionRepository {

    // DAOs
    private val blocks = db.blockDao()
    private val accounts = db.accountDao()
    private val transactions = db.transactionDao()

    // Transaction Flows
    private val allTransactionsFactory = transactions.getAllTransactions().toRefreshable()

    override val receivedTransactions
        get() = flow<List<ConfirmedTransaction>> {
            emitAll(
                transactions.getReceivedTransactions().toRefreshable().toFlowPagedList(pageSize)
            )
        }
    override val sentTransactions
        get() = flow<List<ConfirmedTransaction>> {
            emitAll(transactions.getSentTransactions().toRefreshable().toFlowPagedList(pageSize))
        }
    override val allTransactions
        get() = flow<List<ConfirmedTransaction>> {
            emitAll(allTransactionsFactory.toFlowPagedList(pageSize))
        }

    //
    // TransactionRepository API
    //

    override fun invalidate() = allTransactionsFactory.refresh()

    override suspend fun lastScannedHeight() = blocks.lastScannedHeight()

    override suspend fun firstScannedHeight() = blocks.firstScannedHeight()

    override suspend fun isInitialized() = blocks.count() > 0

    override suspend fun findEncodedTransactionById(txId: Long) =
        transactions.findEncodedTransactionById(txId)

    override suspend fun findNewTransactions(blockHeightRange: IntRange): List<ConfirmedTransaction> =
        transactions.findAllTransactionsByRange(blockHeightRange.first, blockHeightRange.last)

    override suspend fun findMinedHeight(rawTransactionId: ByteArray) =
        transactions.findMinedHeight(rawTransactionId)

    override suspend fun findMatchingTransactionId(rawTransactionId: ByteArray): Long? =
        transactions.findMatchingTransactionId(rawTransactionId)

    override suspend fun cleanupCancelledTx(rawTransactionId: ByteArray) =
        transactions.cleanupCancelledTx(rawTransactionId)

    // let expired transactions linger in the UI for a little while
    override suspend fun deleteExpired(lastScannedHeight: Int) =
        transactions.deleteExpired(lastScannedHeight - (ZcashSdk.EXPIRY_OFFSET / 2))

    override suspend fun count() = transactions.count()

    override suspend fun getAccount(accountId: Int) = accounts.findAccountById(accountId)

    override suspend fun getAccountCount() = accounts.count()

    /**
     * Close the underlying database.
     */
    suspend fun close() {
        withContext(SdkDispatchers.DATABASE_IO) {
            db.close()
        }
    }

    // TODO: begin converting these into Data Access API. For now, just collect the desired operations and iterate/refactor, later
    suspend fun findBlockHash(height: Int): ByteArray? = blocks.findHashByHeight(height)
    suspend fun getTransactionCount(): Int = transactions.count()

    // TODO: convert this into a wallet repository rather than "transaction repository"

    companion object {
        suspend fun new(
            appContext: Context,
            pageSize: Int = 10,
            rustBackend: RustBackend,
            birthday: WalletBirthday,
            viewingKeys: List<UnifiedViewingKey>,
            overwriteVks: Boolean = false,
        ): PagedTransactionRepository {
            initMissingDatabases(rustBackend, birthday, viewingKeys)

            val db = buildDatabase(appContext.applicationContext, rustBackend.pathDataDb)
            applyKeyMigrations(rustBackend, overwriteVks, viewingKeys)

            return PagedTransactionRepository(db, pageSize)
        }

        /**
         * Build the database and apply migrations.
         */
        private suspend fun buildDatabase(context: Context, databasePath: String): DerivedDataDb {
            twig("Building dataDb and applying migrations")
            return Room.databaseBuilder(context, DerivedDataDb::class.java, databasePath)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .setQueryExecutor(SdkExecutors.DATABASE_IO)
                .setTransactionExecutor(SdkExecutors.DATABASE_IO)
                .addMigrations(DerivedDataDb.MIGRATION_3_4)
                .addMigrations(DerivedDataDb.MIGRATION_4_3)
                .addMigrations(DerivedDataDb.MIGRATION_4_5)
                .addMigrations(DerivedDataDb.MIGRATION_5_6)
                .addMigrations(DerivedDataDb.MIGRATION_6_7)
                .build().also {
                    // TODO: document why we do this. My guess is to catch database issues early or to trigger migrations--I forget why it was added but there was a good reason?
                    withContext(SdkDispatchers.DATABASE_IO) {
                        it.openHelper.writableDatabase.beginTransaction()
                        it.openHelper.writableDatabase.endTransaction()
                    }
                }
        }

        /**
         * Create any databases that don't already exist via Rust. Originally, this was done on the Rust
         * side because Rust was intended to own the "dataDb" and Kotlin just reads from it. Since then,
         * it has been more clear that Kotlin should own the data and just let Rust use it.
         */
        private suspend fun initMissingDatabases(
            rustBackend: RustBackend,
            birthday: WalletBirthday,
            viewingKeys: List<UnifiedViewingKey>,
        ) {
            maybeCreateDataDb(rustBackend)
            maybeInitBlocksTable(rustBackend, birthday)
            maybeInitAccountsTable(rustBackend, viewingKeys)
        }

        /**
         * Create the dataDb and its table, if it doesn't exist.
         */
        private suspend fun maybeCreateDataDb(rustBackend: RustBackend) {
            tryWarn("Warning: did not create dataDb. It probably already exists.") {
                rustBackend.initDataDb()
                twig("Initialized wallet for first run file: ${rustBackend.pathDataDb}")
            }
        }

        /**
         * Initialize the blocks table with the given birthday, if needed.
         */
        private suspend fun maybeInitBlocksTable(
            rustBackend: RustBackend,
            birthday: WalletBirthday
        ) {
            // TODO: consider converting these to typed exceptions in the welding layer
            tryWarn(
                "Warning: did not initialize the blocks table. It probably was already initialized.",
                ifContains = "table is not empty"
            ) {
                rustBackend.initBlocksTable(
                    birthday.height,
                    birthday.hash,
                    birthday.time,
                    birthday.tree
                )
                twig("seeded the database with sapling tree at height ${birthday.height}")
            }
            twig("database file: ${rustBackend.pathDataDb}")
        }

        /**
         * Initialize the accounts table with the given viewing keys.
         */
        private suspend fun maybeInitAccountsTable(
            rustBackend: RustBackend,
            viewingKeys: List<UnifiedViewingKey>
        ) {
            // TODO: consider converting these to typed exceptions in the welding layer
            tryWarn(
                "Warning: did not initialize the accounts table. It probably was already initialized.",
                ifContains = "table is not empty"
            ) {
                rustBackend.initAccountsTable(*viewingKeys.toTypedArray())
                twig("Initialized the accounts table with ${viewingKeys.size} viewingKey(s)")
            }
        }

        private suspend fun applyKeyMigrations(
            rustBackend: RustBackend,
            overwriteVks: Boolean,
            viewingKeys: List<UnifiedViewingKey>
        ) {
            if (overwriteVks) {
                twig("applying key migrations . . .")
                maybeInitAccountsTable(rustBackend, viewingKeys)
            }
        }
    }
}
