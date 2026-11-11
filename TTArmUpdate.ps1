Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# === Utility Functions ===
$FirmwareDirectory = Join-Path $PSScriptRoot 'releases'
function Get-LatestZipFiles {
    Get-ChildItem -LiteralPath $FirmwareDirectory -Filter 'TTArm*.zip' -File | Sort-Object LastWriteTime -Descending
}

function Get-ComPorts {
    try {
        return [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
    } catch {
        Write-Host "Error detecting COM ports: $_"
        return @()
    }
}
# === COM Port Setup ===
# ----------------------------------------------------------------------
# 1. Define Connection Parameters (COM8, 115200 Baud, 8 Data Bits, No Parity, 1 Stop Bit)
# ----------------------------------------------------------------------
$BaudRate = 115200
$Parity = [System.IO.Ports.Parity]::None
$DataBits = 8
$StopBits = [System.IO.Ports.StopBits]::One



# === GUI Setup ===
$form = New-Object System.Windows.Forms.Form
$form.Text = "TTArm Firmware Update"
$form.Size = New-Object System.Drawing.Size(400,380)
$form.StartPosition = "CenterScreen"

# ZIP Label
$labelZip = New-Object System.Windows.Forms.Label
$labelZip.Text = "Select Firmware (.zip):"
$labelZip.Location = New-Object System.Drawing.Point(20,20)
$labelZip.AutoSize = $true
$form.Controls.Add($labelZip)

# ZIP Dropdown
$comboZip = New-Object System.Windows.Forms.ComboBox
$comboZip.Location = New-Object System.Drawing.Point(20,45)
$comboZip.Size = New-Object System.Drawing.Size(340,25)
$form.Controls.Add($comboZip)

# Populate ZIP files
function Refresh-ZipFiles {
    $comboZip.Items.Clear()
    $zipFiles = Get-LatestZipFiles
    if ($zipFiles.Count -gt 0) {
        foreach ($z in $zipFiles) { $comboZip.Items.Add($z.Name) }
        $comboZip.SelectedIndex = 0
    } else {
        $comboZip.Items.Add("No .zip files found")
        $comboZip.SelectedIndex = 0
        $comboZip.Enabled = $false
    }
}
Refresh-ZipFiles

# COM Label
# COM Label
$labelCom = New-Object System.Windows.Forms.Label
$labelCom.Text = "Select COM Port:"
$labelCom.Location = New-Object System.Drawing.Point(20,85)
$labelCom.AutoSize = $true
$form.Controls.Add($labelCom)

# COM Dropdown
$comboCom = New-Object System.Windows.Forms.ComboBox
$comboCom.Location = New-Object System.Drawing.Point(20,110)
$comboCom.Size = New-Object System.Drawing.Size(200,25)
$form.Controls.Add($comboCom)

# Manual Scan Button
$buttonScan = New-Object System.Windows.Forms.Button
$buttonScan.Text = "Scan Ports"
$buttonScan.Location = New-Object System.Drawing.Point(240,110)
$buttonScan.Size = New-Object System.Drawing.Size(120,25)
$form.Controls.Add($buttonScan)

# Function to refresh COM ports manually
function Refresh-ComPorts {
    $comboCom.Items.Clear()
    $ports = [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
    if ($ports.Count -gt 0) {
        foreach ($p in $ports) { $comboCom.Items.Add($p) }
        $comboCom.SelectedIndex = 0
        $comboCom.Enabled = $true
    } else {
        $comboCom.Items.Add("No COM ports detected")
        $comboCom.SelectedIndex = 0
        $comboCom.Enabled = $false
    }
}

# Bind the button
$buttonScan.Add_Click({ Refresh-ComPorts })

# Initial scan
Refresh-ComPorts

# Update Button
$buttonUpdate = New-Object System.Windows.Forms.Button
$buttonUpdate.Text = "Connect and Update"
$buttonUpdate.Location = New-Object System.Drawing.Point(20,140)
$buttonUpdate.Size = New-Object System.Drawing.Size(340,35)
$form.Controls.Add($buttonUpdate)

# Status Output
$textOutput = New-Object System.Windows.Forms.TextBox
$textOutput.Location = New-Object System.Drawing.Point(20,180)
$textOutput.Size = New-Object System.Drawing.Size(340,150)
$textOutput.ReadOnly = $true
$textOutput.Multiline = $true
$textOutput.ScrollBars = 'Vertical'
$form.Controls.Add($textOutput)

# === Update Action ===
$buttonUpdate.Add_Click({
    $selectedZip = Join-Path $FirmwareDirectory ([string]$comboZip.SelectedItem)
    $selectedCom = $comboCom.SelectedItem

    # Reset the status text area
    $textOutput.Text = "" 

    if (-not (Test-Path $selectedZip)) {
        [System.Windows.Forms.MessageBox]::Show("Selected zip file not found.","Error")
        return
    }

    if (-not $selectedCom -or $selectedCom -match "No COM") {
        [System.Windows.Forms.MessageBox]::Show("Please select a valid COM port.","Error")
        return
    }

    # Create the SerialPort object
    try {
        $SerialPort = New-Object System.IO.Ports.SerialPort -ArgumentList $selectedCom, $BaudRate, $Parity, $DataBits, $StopBits
        # Set a short timeout for the ReadExisting method. This is less critical 
        # for ReadExisting but can prevent indefinite blocking if using ReadLine.
        $SerialPort.ReadTimeout = 500 # 500 milliseconds
        $SerialPort.Encoding = [System.Text.Encoding]::ASCII # Set encoding to standard ASCII

        $textOutput.AppendText("Connecting to TTArm at $selectedCom ....`r`n")
        Start-Sleep -Milliseconds 500
        $SerialPort.Open()   
        $SerialPort.DtrEnable = $false
        Start-Sleep -Milliseconds 50
        $SerialPort.DtrEnable = $true
        Start-Sleep -Milliseconds 50
        $SerialPort.DtrEnable = $false


        $TTArmDetected = $false
        $timeoutMilliSeconds = 15000
        $startTime = Get-Date           
        while (-not $TTArmDetected) {
            if ($SerialPort.BytesToRead -gt 0) {
                # Read all available data in the buffer immediately
                $Data = $SerialPort.ReadExisting()
            
                If ($Data -match "Initializing channels") {
                    $TTArmDetected=$true
                    $textOutput.AppendText("TTArm detected on $selectedCom `r`n")
                }
                # Check if any data was received before writing to the console
                if ($Data -ne "") {
                    # Use -NoNewLine to display the raw stream without extra line breaks 
                    # (the device's own line endings will determine formatting)
                    # Write-Host $Data -NoNewLine
                }            
                
            }
            # Pause briefly to prevent the loop from consuming 100% CPU
            Start-Sleep -Milliseconds 100
            $currentTime = Get-Date
            $elapsedTime = New-TimeSpan -start $startTime -end $currentTime
            # Write-Host "Loop time = $elapsedTime.TotalMilliSeconds"
            $textOutput.AppendText(".")
            if ($elapsedTime.TotalMilliseconds -ge $timeoutMilliSeconds) {
                $textOutput.AppendText("`r`n-----------------------------`r`n")  
                $textOutput.AppendText("Timeout on $selectedCom `r`n")            
                break
            }
        }
    }
    catch {
        $textOutput.AppendText("ERROR: Failed to open or communicate with $selectedCom.`r`n")
        return
    }
    finally {
        if ($SerialPort -ne $null -and $SerialPort.IsOpen) {
            $SerialPort.Close()
        }
        if ($SerialPort -ne $null) {
            $SerialPort.Dispose()
            $SerialPort = $null # Explicitly clear the reference
        }
    }
    
    if (-not $TTArmDetected) {
        $textOutput.AppendText("TTArm not found, try another COM port`r`n")
        return
    }

    # =========================================================
    # NEW CONFIRMATION DIALOG
    # =========================================================
    $ConfirmationResult = [System.Windows.Forms.MessageBox]::Show(
        "TTArm detected on $selectedCom. `r`nFirmware: '$selectedZip'.`n`nAre you sure you want to proceed with the update?", 
        "Confirm Firmware Update", 
        [System.Windows.Forms.MessageBoxButtons]::YesNo, 
        [System.Windows.Forms.MessageBoxIcon]::Question
    )

    if ($ConfirmationResult -ne [System.Windows.Forms.DialogResult]::Yes) {
        $textOutput.AppendText("Update cancelled by user.`r`n")
        return # Stop execution if the user selects 'No'
    }

# --- DFU Update Logic with Streaming Output ---
    $NL = "`r`n"
    $textOutput.AppendText("Updating... Please wait." + $NL)
    Start-Sleep -Milliseconds 200
    
 # --- DFU Update Logic with File Monitoring (Stable Streaming) ---
    $NL = "`r`n"
    $textOutput.AppendText("Updating... Please wait." + $NL)
    Start-Sleep -Milliseconds 200
    
    # 1. SETUP LOG FILE AND COMMAND
    $LogFile = [System.IO.Path]::GetTempFileName() + ".log"
    $ExitCode = -1
    
    # Build DFU command with redirection to log file
    # We use a PowerShell command that executes the DFU tool and redirects all output to the log file
    $DFUCommand = ".\adafruit-nrfutil dfu serial --package `"$selectedZip`" -p $selectedCom -b 115200"
    
    # The command to execute in the new process: execute the DFU tool and redirect all streams to the log file.
    # The command execution will be: cmd /c ".\adafruit-nrfutil ... > $LogFile 2>&1"
    $cmdToRun = "cmd /c `"$DFUCommand`" > `"$LogFile`" 2>&1"

    $textOutput.AppendText("DFU Output being logged to: $LogFile" + $NL)
    $textOutput.AppendText("Command: $DFUCommand" + $NL)


    # 2. START PROCESS (Non-blocking)
    $process = Start-Process "cmd" -ArgumentList "/c", $cmdToRun -WorkingDirectory $PSScriptRoot -NoNewWindow -PassThru -Wait:$false
    #$process = Start-Process -FilePath "cmd.exe" -ArgumentList "/c $DFUCommand" -RedirectStandardOutput $LogFile -NoNewWindow -PassThru -Wait:$false

    $textOutput.AppendText("--- DFU Output (Live Stream) ---" + $NL)

    # 3. MONITOR AND STREAM OUTPUT
    $LastLogPosition = 0
    $FileStream = $null
    $StreamReader = $null

    $ExitCode = -1
    
    try {
        # Loop while the DFU process is still running
        while (-not $process.HasExited) {
            
            # Read new output from the log file
            # This must be done carefully to avoid file lock contention
            
            # Re-open and close the file stream for each read to avoid file lock issues
            if ([System.IO.File]::Exists($LogFile)) {
                $FileStream = [System.IO.File]::Open($LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                $StreamReader = New-Object System.IO.StreamReader($FileStream)
                
                # Move to the last read position
                $FileStream.Seek($LastLogPosition, [System.IO.SeekOrigin]::Begin)
                
                while ($line = $StreamReader.ReadLine()) {
                    # Append the new line to the text box
                    $textOutput.AppendText($line)
                    $form.Refresh() # CRITICAL: Force the GUI to update
                    $LastLogPosition = $FileStream.Position
                    If ($line -match "Device programmed.") {
                    $ExitCode = 0                    
                    }
                }
                
                $StreamReader.Close()
                $FileStream.Close()
            }
            
            # Sleep briefly to reduce CPU load and allow the GUI to breathe
            Start-Sleep -Milliseconds 200 
        }

        # 4. FINAL READ AND EXIT CODE CHECK
        
        # Read any remaining output after the process exits
        if ([System.IO.File]::Exists($LogFile)) {
            $FileStream = [System.IO.File]::Open($LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
            $StreamReader = New-Object System.IO.StreamReader($FileStream)
            $FileStream.Seek($LastLogPosition, [System.IO.SeekOrigin]::Begin)

            while ($line = $StreamReader.ReadLine()) {
                $textOutput.AppendText($line + $NL)
                $form.Refresh()
                If ($line -match "Device programmed.") {
                    $ExitCode = 0                    
                }
            }
            
            $StreamReader.Close()
            $FileStream.Close()
        }

        # Get the exit code
        # $ExitCode = $process.ExitCode
        
        $textOutput.AppendText("------------------------------" + $NL)

        if ($ExitCode -eq 0) {
            $textOutput.AppendText("Update complete!" + $NL)
        } else {
            $textOutput.AppendText("Error: DFU failed. " + $NL)
        }
    } 
    catch {
        $textOutput.AppendText("FATAL ERROR during DFU streaming: $($_.Exception.Message)" + $NL)
    }
    finally {
        # CRITICAL: Clean up the temporary log file
        if (Test-Path $LogFile) {
            Remove-Item $LogFile -Force -ErrorAction SilentlyContinue
        }
    }
})


# Show GUI
$form.Topmost = $true
[void]$form.ShowDialog()
