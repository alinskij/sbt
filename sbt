#!/usr/bin/env bash
# sbt - Simple Backup Tool

# Configuration
CONFIG_DIR="$HOME/.config/sbt"
BACKUP_CONFIG="$CONFIG_DIR/backup_paths"
STORAGE_CONFIG="$CONFIG_DIR/storage_path"
DEFAULT_STORAGE="$HOME/sbt_backups"

# Initialize
init_config() {
    [ -d "$CONFIG_DIR" ] || mkdir -p "$CONFIG_DIR"
    [ -f "$BACKUP_CONFIG" ] || touch "$BACKUP_CONFIG"
    [ -f "$STORAGE_CONFIG" ] || echo "$DEFAULT_STORAGE" > "$STORAGE_CONFIG"
    [ -f "$CONFIG_DIR/created_backups" ] || touch "$CONFIG_DIR/created_backups"
    [ -f "$CONFIG_DIR/sbt.log" ] || touch "$CONFIG_DIR/sbt.log"
    
    local storage_path=$(get_storage_path)
    [ -d "$storage_path" ] || mkdir -p "$storage_path"
    
    log_message "INFO" "Simple Backup Tool initialized"
}

# Get current storage path
get_storage_path() {
    if [ -f "$STORAGE_CONFIG" ] && [ -s "$STORAGE_CONFIG" ]; then
        head -n 1 "$STORAGE_CONFIG"
    else
        echo "$DEFAULT_STORAGE"
    fi
}

# Log message to file
log_message() {
    local level="$1"  # INFO, WARN, ERROR, DEBUG
    local message="$2"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] $level: $message" >> "$CONFIG_DIR/sbt.log"
}

# Clean exit function
clean_exit() {
    tput reset
    exit 0
}

# Main menu
main_menu() {
    while true; do
        CHOICE=$(dialog --clear \
            --title "Simple Backup Tool" \
            --menu "Choose an option:" \
            20 60 10 \
            1 "Create a backup" \
            2 "Restore backup" \
            3 "Configure backup paths" \
            4 "Set backup storage location" \
            5 "View usage info" \
            6 "Exit" \
            2>&1 >/dev/tty)

        case $CHOICE in
            1) backup_menu ;;
            2) restore_menu ;;
            3) configure_paths_menu ;;
            4) configure_storage_menu ;;
            5) info_menu ;;
            6) clean_exit ;;
            *) dialog --msgbox "Invalid choice!" 5 30 ;;
        esac
    done
}

# Storage configuration menu
configure_storage_menu() {
    CURRENT_PATH=$(get_storage_path)
    
    dialog --title "Current Storage Location" \
        --msgbox "Current backup storage path:\n\n$CURRENT_PATH" 10 60
    
    NEW_PATH=$(dialog --title "Set New Storage Location" \
        --dselect "$CURRENT_PATH" 20 60 \
        2>&1 >/dev/tty)
    
    [ $? -ne 0 ] && return  # User cancelled
    
    # Validate path
    if [[ "$NEW_PATH" =~ ^/ ]]; then
        echo "$NEW_PATH" > "$STORAGE_CONFIG"
        mkdir -p "$NEW_PATH"
        log_message "INFO" "Storage location updated to $NEW_PATH"
        dialog --msgbox "Storage location updated to:\n\n$NEW_PATH" 8 60
    else
        log_message "ERROR" "Invalid storage path: $NEW_PATH"
        dialog --msgbox "Error: Path must be absolute (start with /)" 6 60
    fi
}

# Path configuration menu
configure_paths_menu() {
    TMPFILE=$(mktemp)
    [ -f "$BACKUP_CONFIG" ] && cp "$BACKUP_CONFIG" "$TMPFILE"
    
    dialog --title "Configure Backup Paths" \
        --editbox "$TMPFILE" 20 60 2> "$BACKUP_CONFIG"
    
    log_message "INFO" "Backup paths configuration updated"
    rm "$TMPFILE"
}

# Backup menu
backup_menu() {
    if [ ! -f "$BACKUP_CONFIG" ] || [ ! -s "$BACKUP_CONFIG" ]; then
        log_message "ERROR" "No backup paths configured"
        dialog --msgbox "Error: No backup paths configured!\nPlease add paths in configuration menu first." 7 60
        configure_paths_menu
        [ ! -s "$BACKUP_CONFIG" ] && return
    fi

    VALID_PATHS=()
    while IFS= read -r path; do
        if [ -n "$path" ]; then
            if [ -e "$path" ]; then
                VALID_PATHS+=("$path")
            else
                log_message "WARN" "Path $path not found, skipping"
            fi
        fi
    done < "$BACKUP_CONFIG"

    if [ ${#VALID_PATHS[@]} -eq 0 ]; then
        log_message "ERROR" "All configured paths are invalid"
        dialog --msgbox "Error: All configured paths are invalid!\nPlease check your configuration." 7 60
        return
    fi

    PATHS_LIST=$(printf "• %s\n" "${VALID_PATHS[@]}")
    STORAGE_PATH=$(get_storage_path)
    
    dialog --title "Backup Confirmation" \
        --yesno "The following items will be backed up:\n\n$PATHS_LIST\n\nBackup will be saved to:\n$STORAGE_PATH\n\nProceed?" \
        20 60 || return

    BACKUP_FILE="$STORAGE_PATH/backup_$(date +%Y%m%d_%H%M).tar.xz.gpg"
    log_message "INFO" "Starting backup to $BACKUP_FILE"
    
    dialog --infobox "Creating backup, please wait..." 5 40
    
    if tar -cf - --warning=no-all "${VALID_PATHS[@]}" 2>/dev/null | \
       xz --threads=$(nproc) 2>/dev/null | \
       gpg --default-recipient-self --encrypt --sign 2>/dev/null > "$BACKUP_FILE"; then
        log_message "INFO" "Backup created successfully: $BACKUP_FILE"
        dialog --msgbox "Backup created successfully!\n\nLocation: $BACKUP_FILE" 8 60
        echo "$BACKUP_FILE" >> "$CONFIG_DIR/created_backups"
    else
        log_message "ERROR" "Backup failed for $BACKUP_FILE"
        dialog --msgbox "Backup failed!" 5 30
    fi
}

# Restore menu
restore_menu() {
    if [ ! -f "$CONFIG_DIR/created_backups" ] || [ ! -s "$CONFIG_DIR/created_backups" ]; then
        log_message "ERROR" "No backups found"
        dialog --msgbox "No backups found!\nPlease create a backup first." 7 60
        return
    fi

    BACKUPS=()
    INDEX=1
    while IFS= read -r backup; do
        if [ -n "$backup" ] && [ -f "$backup" ]; then
            BACKUPS+=("$INDEX" "$backup")
            ((INDEX++))
        else
            log_message "WARN" "Backup file $backup not found, skipping"
        fi
    done < "$CONFIG_DIR/created_backups"

    if [ ${#BACKUPS[@]} -eq 0 ]; then
        log_message "ERROR" "No valid backups found"
        dialog --msgbox "No valid backups found!\nPreviously created backups may have been deleted." 7 60
        return
    fi

    SELECTED_BACKUP=$(dialog --title "Restore Backup" \
        --menu "Select a backup to restore:" 20 60 10 \
        "${BACKUPS[@]}" \
        2>&1 >/dev/tty)

    [ $? -ne 0 ] && return

    BACKUP_FILE=$(sed -n "${SELECTED_BACKUP}p" "$CONFIG_DIR/created_backups")

    if [ ! -f "$BACKUP_FILE" ]; then
        log_message "ERROR" "Selected backup file $BACKUP_FILE does not exist"
        dialog --msgbox "Error: Selected backup file does not exist!" 6 60
        return
    fi

    STORAGE_PATH=$(get_storage_path)
    RESTORE_PATH="$STORAGE_PATH/restore_$(date +%Y%m%d_%H%M)"

    RESTORE_PATH=$(dialog --title "Restore Location" \
        --dselect "$RESTORE_PATH" 20 60 \
        2>&1 >/dev/tty)

    [ $? -ne 0 ] && return

    if [[ ! "$RESTORE_PATH" =~ ^/ ]]; then
        log_message "ERROR" "Invalid restore path: $RESTORE_PATH"
        dialog --msgbox "Error: Restore path must be absolute (start with /)" 6 60
        return
    fi

    mkdir -p "$RESTORE_PATH" || {
        log_message "ERROR" "Could not create restore directory: $RESTORE_PATH"
        dialog --msgbox "Error: Could not create restore directory!" 6 60
        return
    }

    log_message "INFO" "Starting restore from $BACKUP_FILE to $RESTORE_PATH"
    dialog --infobox "Restoring backup, please wait..." 5 40

    if gpg --decrypt "$BACKUP_FILE" 2>/dev/null | xz -d 2>/dev/null | tar -xf - -C "$RESTORE_PATH" 2>/dev/null; then
        log_message "INFO" "Backup restored successfully to $RESTORE_PATH"
        dialog --msgbox "Backup restored successfully!\n\nLocation: $RESTORE_PATH" 8 60
    else
        log_message "ERROR" "Restore failed for $BACKUP_FILE"
        dialog --msgbox "Restore failed! Check your GPG key or backup file integrity." 7 60
    fi
}

# Info menu
info_menu() {
    dialog --title "Usage Information" \
        --msgbox "Key information for using Simple Backup Tool:\n\
1. GPG Keys: Ensure you have valid GPG keys for encryption (--default-recipient-self). See the manual for help: https://wiki.archlinux.org/title/GnuPG\n\
2. Paths: Verify that configured backup paths exist to avoid errors.\n\
3. Storage: Set an absolute path for backup storage (starts with /).\n\
4. Backups: Created backups are listed in 'Restore backup' menu." \
        15 70
}

# Initialize configuration
init_config

# Handle interrupts
trap clean_exit SIGINT SIGTERM

# Start main menu
main_menu
