package com.github.reygnn.b2b.di

import android.content.Context
import androidx.room.Room
import com.github.reygnn.b2b.data.auth.PkceAuthManager
import com.github.reygnn.b2b.data.auth.TokenStore
import com.github.reygnn.b2b.data.auth.TokenStoreImpl
import com.github.reygnn.b2b.data.local.AppDatabase
import com.github.reygnn.b2b.data.local.dao.PoolTrackDao
import com.github.reygnn.b2b.data.local.dao.RecentlyPlayedDao
import com.github.reygnn.b2b.data.local.dao.WhitelistDao
import com.github.reygnn.b2b.data.remote.AuthInterceptor
import com.github.reygnn.b2b.data.remote.SpotifyApi
import com.github.reygnn.b2b.data.repository.ArtistRepositoryImpl
import com.github.reygnn.b2b.data.repository.PlaybackRepositoryImpl
import com.github.reygnn.b2b.data.repository.PoolRepositoryImpl
import com.github.reygnn.b2b.data.repository.RecentlyPlayedRepositoryImpl
import com.github.reygnn.b2b.domain.repository.ArtistRepository
import com.github.reygnn.b2b.domain.repository.PlaybackRepository
import com.github.reygnn.b2b.domain.repository.PoolRepository
import com.github.reygnn.b2b.domain.repository.RecentlyPlayedRepository
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "b2b.db").build()

    @Provides fun whitelistDao(db: AppDatabase): WhitelistDao = db.whitelistDao()
    @Provides fun poolTrackDao(db: AppDatabase): PoolTrackDao = db.poolTrackDao()
    @Provides fun recentlyPlayedDao(db: AppDatabase): RecentlyPlayedDao = db.recentlyPlayedDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.spotify.com/"

    @Provides @Singleton fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides @Singleton
    fun okHttp(tokenStore: TokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton
    fun spotifyApi(retrofit: Retrofit): SpotifyApi = retrofit.create(SpotifyApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    @Binds abstract fun bindArtistRepository(impl: ArtistRepositoryImpl): ArtistRepository
    @Binds abstract fun bindPoolRepository(impl: PoolRepositoryImpl): PoolRepository
    @Binds abstract fun bindRecentlyPlayedRepository(impl: RecentlyPlayedRepositoryImpl): RecentlyPlayedRepository
    @Binds abstract fun bindPlaybackRepository(impl: PlaybackRepositoryImpl): PlaybackRepository
    @Binds abstract fun bindTokenStore(impl: TokenStoreImpl): TokenStore
}
