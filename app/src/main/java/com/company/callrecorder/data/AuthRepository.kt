package com.company.callrecorder.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class AppUser(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoURL: String? = null,
    val role: String = "user",
    val status: String = "pending",
    val createdAt: Long = 0
)

class AuthRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val credentialManager = CredentialManager.create(context)

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _appUser = MutableStateFlow<AppUser?>(null)
    val appUser: StateFlow<AppUser?> = _appUser.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    companion object {
        const val WEB_CLIENT_ID = "413448527726-ctac2753m22uvocs1ofdorskh7ujvrqk.apps.googleusercontent.com"
    }

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            if (firebaseAuth.currentUser != null) {
                loadAppUser(firebaseAuth.currentUser!!.uid)
            } else {
                _appUser.value = null
                _isLoading.value = false
            }
        }
    }

    private fun loadAppUser(uid: String) {
        firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _appUser.value = AppUser(
                        uid = snapshot.getString("uid") ?: "",
                        email = snapshot.getString("email") ?: "",
                        displayName = snapshot.getString("displayName") ?: "",
                        photoURL = snapshot.getString("photoURL"),
                        role = snapshot.getString("role") ?: "user",
                        status = snapshot.getString("status") ?: "pending",
                        createdAt = snapshot.getLong("createdAt") ?: 0
                    )
                }
                _isLoading.value = false
            }
    }

    suspend fun signInWithGoogle(): Result<FirebaseUser> {
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            handleSignInResult(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun handleSignInResult(result: GetCredentialResponse): Result<FirebaseUser> {
        return try {
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {

                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val user = authResult.user!!

                // Firestore에 사용자 정보 저장/업데이트
                saveUserToFirestore(user)

                Result.success(user)
            } else {
                Result.failure(Exception("Invalid credential type"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun saveUserToFirestore(user: FirebaseUser) {
        val userDoc = firestore.collection("users").document(user.uid)
        val snapshot = userDoc.get().await()

        if (!snapshot.exists()) {
            // 첫 번째 사용자인지 확인
            val usersSnapshot = firestore.collection("users").get().await()
            val isFirstUser = usersSnapshot.isEmpty

            val newUser = hashMapOf(
                "uid" to user.uid,
                "email" to (user.email ?: ""),
                "displayName" to (user.displayName ?: ""),
                "photoURL" to user.photoUrl?.toString(),
                "role" to if (isFirstUser) "admin" else "user",
                "status" to if (isFirstUser) "approved" else "pending",
                "createdAt" to System.currentTimeMillis()
            )
            userDoc.set(newUser).await()
        }
    }

    fun signOut() {
        auth.signOut()
        _appUser.value = null
    }

    fun isApproved(): Boolean {
        return _appUser.value?.status == "approved"
    }

    fun getUserInfo(): Pair<String, String>? {
        val user = _appUser.value ?: return null
        return Pair(user.displayName, user.uid)
    }
}
