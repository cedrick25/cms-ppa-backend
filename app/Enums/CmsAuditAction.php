<?php

namespace App\Enums;

enum CmsAuditAction: string
{
    case SignedIn = 'signed_in';
    case SignedOut = 'signed_out';
    case RemoteDeskOpened = 'remote_desk_opened';
    case CredentialsRegistered = 'credentials_registered';
    case CredentialsUpdated = 'credentials_updated';
    case CredentialsRemoved = 'credentials_removed';
    case UserRegistered = 'user_registered';
    case UserUpdated = 'user_updated';
    case UserDisabled = 'user_disabled';
    case UserRemoved = 'user_removed';

    public function module(): CmsAuditModule
    {
        return match ($this) {
            self::SignedIn, self::SignedOut => CmsAuditModule::Session,
            self::RemoteDeskOpened, self::CredentialsRegistered, self::CredentialsUpdated, self::CredentialsRemoved => CmsAuditModule::Credentials,
            self::UserRegistered, self::UserUpdated, self::UserDisabled, self::UserRemoved => CmsAuditModule::Users,
        };
    }

    public function label(): string
    {
        return match ($this) {
            self::SignedIn => 'Signed in',
            self::SignedOut => 'Signed out',
            self::RemoteDeskOpened => 'Opened remote desk',
            self::CredentialsRegistered => 'Registered credentials',
            self::CredentialsUpdated => 'Updated credentials',
            self::CredentialsRemoved => 'Removed credentials',
            self::UserRegistered => 'Registered account',
            self::UserUpdated => 'Updated account',
            self::UserDisabled => 'Disabled account',
            self::UserRemoved => 'Removed account',
        };
    }
}
