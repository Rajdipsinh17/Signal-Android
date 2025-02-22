package org.thoughtcrime.securesms.components.settings.app.account.export

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.core.JsonParseException
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.dependencies.MockApplicationDependencyProvider
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.KeyValueDataSet
import org.thoughtcrime.securesms.keyvalue.KeyValueStore
import org.thoughtcrime.securesms.keyvalue.MockKeyValuePersistentStorage
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.util.JsonUtils
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class ExportAccountDataTest {

  private val mockJson: String = """
{
    "reportId": "4c0ca2aa-151b-4e9e-8bf4-ea2c64345a22",
    "reportTimestamp": "2023-03-22T20:21:24Z",
    "data": {
        "account": {
            "phoneNumber": "+14125556950",
            "badges": [
                {
                    "id": "R_LOW",
                    "expiration": "2023-04-27T00:00:00Z",
                    "visible": true
                }
            ],
            "allowSealedSenderFromAnyone": false,
            "findAccountByPhoneNumber": true
        },
        "devices": [
            {
                "id": 1,
                "lastSeen": "2023-03-22T00:00:00Z",
                "created": "2023-03-07T19:37:08Z",
                "userAgent": "OWA"
            },
            {
                "id": 2,
                "lastSeen": "2023-03-21T00:00:00Z",
                "created": "2023-03-07T19:40:56Z",
                "userAgent": null
            }
        ]
    },
    "text": "Report ID: 4c0ca2aa-151b-4e9e-8bf4-ea2c64345a22\nReport timestamp: 2023-03-22T20:21:24Z\n\n# Account\nPhone number: +16509246950\nAllow sealed sender from anyone: false\nFind account by phone number: true\nBadges:\n- ID: R_LOW\n  Expiration: 2023-04-27T00:00:00Z\n  Visible: true\n\n# Devices\n- ID: 1\n  Created: 2023-03-07T19:37:08Z\n  Last seen: 2023-03-22T00:00:00Z\n  User-agent: OWA\n- ID: 2\n  Created: 2023-03-07T19:40:56Z\n  Last seen: 2023-03-21T00:00:00Z\n  User-agent: null\n"
}
  """

  @Before
  fun setup() {
    if (!ApplicationDependencies.isInitialized()) {
      ApplicationDependencies.init(ApplicationProvider.getApplicationContext(), MockApplicationDependencyProvider())
    }
  }

  @Test
  fun `Successful download flow with progress states and delete flow`() {
    val dataSet = KeyValueDataSet()
    val scheduler = TestScheduler()
    val mockRepository: ExportAccountDataRepository = mockk {
      every { downloadAccountDataReport() } returns Completable.create {
        dataSet.putString(AccountValues.KEY_ACCOUNT_DATA_REPORT, mockJson)
        dataSet.putLong(AccountValues.KEY_ACCOUNT_DATA_REPORT_DOWNLOAD_TIME, 123456L)
        it.onComplete()
      }.subscribeOn(scheduler)
    }

    SignalStore.inject(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet)))
    RxAndroidPlugins.setMainThreadSchedulerHandler {
      scheduler
    }

    val viewModel = ExportAccountDataViewModel(mockRepository)
    assertFalse(viewModel.state.value.reportDownloaded)
    viewModel.onDownloadReport()
    assertTrue(viewModel.state.value.downloadInProgress)
    scheduler.triggerActions()
    assertTrue(viewModel.state.value.reportDownloaded)
    assertFalse(viewModel.state.value.downloadInProgress)

    assertEquals(SignalStore.account().accountDataReport, mockJson)
    viewModel.deleteReport()
    assertEquals(SignalStore.account().accountDataReport, null)
    assertFalse(viewModel.state.value.reportDownloaded)
  }

  @Test
  fun `Export json without text field`() {
    val dataSet = KeyValueDataSet()
    dataSet.putString(AccountValues.KEY_ACCOUNT_DATA_REPORT, mockJson)
    SignalStore.inject(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet)))

    val mockRepository: ExportAccountDataRepository = mockk {
      every { generateAccountDataReport(any()) } answers { callOriginal() }
    }
    val viewModel = ExportAccountDataViewModel(mockRepository)

    viewModel.setExportAsTxt()
    val txtReport = viewModel.onGenerateReport()
    assertEquals(txtReport.mimeType, "text/plain")
    assertThrows(JsonParseException::class.java) {
      JsonUtils.getMapper().readTree(BlobProvider.getInstance().getMemoryBlob(txtReport.uri) as ByteArray)
    }

    viewModel.setExportAsJson()
    val jsonReport = viewModel.onGenerateReport()
    assertEquals(jsonReport.mimeType, "application/json")
    val json = JsonUtils.getMapper().readTree(BlobProvider.getInstance().getMemoryBlob(jsonReport.uri) as ByteArray)
    assertFalse(json.has("text"))
    assertTrue(json.has("data"))
    assertTrue(json.has("reportId"))
    assertTrue(json.has("reportTimestamp"))
  }

  @Test
  fun `Failed download error flow`() {
    val dataSet = KeyValueDataSet()
    val scheduler = TestScheduler()
    val mockRepository: ExportAccountDataRepository = mockk {
      every { downloadAccountDataReport() } returns Completable.create {
        it.onError(IOException())
      }.subscribeOn(scheduler)
    }

    SignalStore.inject(KeyValueStore(MockKeyValuePersistentStorage.withDataSet(dataSet)))
    RxAndroidPlugins.setMainThreadSchedulerHandler {
      scheduler
    }

    val viewModel = ExportAccountDataViewModel(mockRepository)
    assertEquals(viewModel.state.value.reportDownloaded, false)
    viewModel.onDownloadReport()
    assertEquals(viewModel.state.value.downloadInProgress, true)
    scheduler.triggerActions()
    assertEquals(viewModel.state.value.reportDownloaded, false)
    assertEquals(viewModel.state.value.downloadInProgress, false)

    assertEquals(viewModel.state.value.showDownloadFailedDialog, true)
    viewModel.dismissDownloadErrorDialog()
    assertEquals(viewModel.state.value.showDownloadFailedDialog, false)
  }
}
