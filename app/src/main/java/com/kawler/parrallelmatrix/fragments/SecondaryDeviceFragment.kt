package com.kawler.parrallelmatrix.fragments

import androidx.fragment.app.viewModels
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.kawler.parrallelmatrix.R
import com.kawler.parrallelmatrix.databinding.FragmentSecondaryDeviceBinding

class SecondaryDeviceFragment : Fragment() {

    private var _binding: FragmentSecondaryDeviceBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SecondaryDeviceViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecondaryDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[SecondaryDeviceViewModel::class.java]

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            binding.progressBar.progress = progress
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            binding.resultTextView.text = result
        }

        viewModel.status.observe(viewLifecycleOwner) { status ->
            binding.statusTextView.text = status
        }

        viewModel.deviceIdValid.observe(viewLifecycleOwner){isValid ->
            binding.progressBar.isVisible = isValid
            binding.resultTextView.isVisible = isValid
            binding.statusTextView.isVisible = isValid
        }
        viewModel.setDeviceId()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}