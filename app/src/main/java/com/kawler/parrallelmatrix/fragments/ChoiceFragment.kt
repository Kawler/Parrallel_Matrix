package com.kawler.parrallelmatrix.fragments

import androidx.fragment.app.viewModels
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.kawler.parrallelmatrix.R
import com.kawler.parrallelmatrix.databinding.FragmentChoiceBinding

class ChoiceFragment : Fragment() {

    private var _binding: FragmentChoiceBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChoiceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mainDeviceButton.setOnClickListener {
            findNavController().navigate(R.id.action_choiceFragment_to_mainDeviceFragment)
        }

        binding.secondaryDeviceButton.setOnClickListener {
            findNavController().navigate(R.id.action_choiceFragment_to_secondaryDeviceFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}