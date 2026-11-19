package com.eddyizm.tempus.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.homemusic.HomeOffline;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Directory;
import com.eddyizm.tempus.subsonic.models.Indexes;
import com.eddyizm.tempus.subsonic.models.MusicFolder;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DirectoryRepository {
    private static final String TAG = "DirectoryRepository";

    public MutableLiveData<List<MusicFolder>> getMusicFolders() {
        MutableLiveData<List<MusicFolder>> liveMusicFolders = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getMusicFolders()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getMusicFolders() != null) {
                            liveMusicFolders.setValue(HomeOffline.musicFolders(response.body().getSubsonicResponse().getMusicFolders().getMusicFolders()));
                        } else {
                            liveMusicFolders.setValue(HomeOffline.musicFolders(null)); // HomeMusic: the remembered folders
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        liveMusicFolders.setValue(HomeOffline.musicFolders(null)); // HomeMusic: the remembered folders
                    }
                });

        return liveMusicFolders;
    }

    public MutableLiveData<Indexes> getIndexes(String musicFolderId, Long ifModifiedSince) {
        MutableLiveData<Indexes> liveIndexes = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getIndexes(musicFolderId, ifModifiedSince)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        // HomeMusic: without the server, the artists of the downloads
                        liveIndexes.setValue(HomeOffline.indexes(response.isSuccessful() && response.body() != null ? response.body().getSubsonicResponse().getIndexes() : null));
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        liveIndexes.setValue(HomeOffline.indexes(null));
                    }
                });

        return liveIndexes;
    }

    public MutableLiveData<Directory> getMusicDirectory(String id) {
        MutableLiveData<Directory> liveMusicDirectory = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getMusicDirectory(id)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        // HomeMusic: without the server, only what is on the phone
                        liveMusicDirectory.setValue(HomeOffline.directory(id, response.isSuccessful() && response.body() != null ? response.body().getSubsonicResponse().getDirectory() : null));
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        t.printStackTrace();
                        liveMusicDirectory.setValue(HomeOffline.directory(id, null));
                    }
                });

        return liveMusicDirectory;
    }
}
