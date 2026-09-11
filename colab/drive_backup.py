import os
import logging
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

logger = logging.getLogger(__name__)

SCOPES = ['https://www.googleapis.com/auth/drive.file']

def authenticate_drive():
    """Authenticate to Google Drive using OAuth2."""
    creds = None
    if os.path.exists('token.json'):
        creds = Credentials.from_authorized_user_file('token.json', SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(
                'credentials.json', SCOPES)
            creds = flow.run_local_server(port=0)
        with open('token.json', 'w') as token:
            token.write(creds.to_json())
    
    return build('drive', 'v3', credentials=creds)

def get_or_create_folder(service, folder_name):
    """Finds or creates a folder on Google Drive."""
    query = f"mimeType='application/vnd.google-apps.folder' and name='{folder_name}' and trashed=false"
    results = service.files().list(q=query, spaces='drive', fields='nextPageToken, files(id, name)').execute()
    items = results.get('files', [])
    
    if not items:
        file_metadata = {
            'name': folder_name,
            'mimeType': 'application/vnd.google-apps.folder'
        }
        folder = service.files().create(body=file_metadata, fields='id').execute()
        return folder.get('id')
    else:
        return items[0].get('id')

def backup_file(local_path: str, drive_folder: str = 'AGY Chat Backups'):
    """Backs up a file to a specific Google Drive folder."""
    try:
        service = authenticate_drive()
        folder_id = get_or_create_folder(service, drive_folder)
        
        file_name = os.path.basename(local_path)
        file_metadata = {
            'name': file_name,
            'parents': [folder_id]
        }
        media = MediaFileUpload(local_path, resumable=True)
        file = service.files().create(body=file_metadata, media_body=media, fields='id').execute()
        logger.info(f"Backed up {local_path} to Drive ID: {file.get('id')}")
        return file.get('id')
    except Exception as e:
        logger.error(f"Failed to backup file {local_path}: {e}")
        return None

def list_backups(drive_folder: str = 'AGY Chat Backups'):
    """Lists files in the backup folder."""
    try:
        service = authenticate_drive()
        folder_id = get_or_create_folder(service, drive_folder)
        
        query = f"'{folder_id}' in parents and trashed=false"
        results = service.files().list(q=query, spaces='drive', fields='nextPageToken, files(id, name)').execute()
        return results.get('files', [])
    except Exception as e:
        logger.error(f"Failed to list backups: {e}")
        return []
