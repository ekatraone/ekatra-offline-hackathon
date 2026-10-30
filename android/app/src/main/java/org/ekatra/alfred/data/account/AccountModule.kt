package org.ekatra.alfred.data.account

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {

    @Binds
    abstract fun bindAccountRemote(impl: FirebaseAccountRemote): AccountRemote

    @Binds
    abstract fun bindLocalAccountData(impl: RoomAccountData): LocalAccountData
}
